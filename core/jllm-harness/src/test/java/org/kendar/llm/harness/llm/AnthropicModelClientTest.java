package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicModelClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final Queue<String> bodies = new ConcurrentLinkedQueue<>();
    private final Queue<String> keys = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int[] statuses = {200};
    private volatile String retryAfter = null;
    private volatile String okBody = "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2}}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            int n = calls.getAndIncrement();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(ex.getRequestHeaders().getFirst("x-api-key") + "|"
                    + ex.getRequestHeaders().getFirst("anthropic-version"));
            int st = statuses[Math.min(n, statuses.length - 1)];
            byte[] out = (st == 200 ? okBody : "{\"type\":\"error\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            if (st != 200 && retryAfter != null) ex.getResponseHeaders().add("retry-after", retryAfter);
            ex.sendResponseHeaders(st, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicModelClient client(int retries, boolean citations, int thinking) {
        AnthropicSettings p = new AnthropicSettings("k-123", "http://127.0.0.1:" + server.getAddress().getPort(),
                "claude-test", 2048, retries, "1h", thinking, 10, citations);
        return new AnthropicModelClient(p, mapper, 1);
    }

    private ArrayNode msgs(String json) throws Exception {
        return (ArrayNode) mapper.readTree(json);
    }

    private HarnessTool tool(String name) {
        return new HarnessTool() {
            public String name() { return name; }
            public String description() { return "d"; }
            public JsonNode inputSchema() {
                try { return mapper.readTree("{\"type\":\"object\"}"); } catch (Exception e) { throw new RuntimeException(e); }
            }
            public String group() { return "g"; }
            public ToolKind kind() { return ToolKind.READ_ONLY; }
            public String execute(ToolContext ctx, JsonNode input) { return ""; }
        };
    }

    private JsonNode lastBody() throws Exception {
        String last = null;
        for (String b : bodies) last = b;
        return mapper.readTree(last);
    }

    private ModelRequest req(String toolChoice, boolean noParallel) throws Exception {
        return new ModelRequest("pin", "extra", msgs("[{\"role\":\"user\",\"content\":\"q\"}]"),
                List.of(tool("a"), tool("b")), 0, toolChoice, noParallel);
    }

    @Test
    void finishedTurnsLoseThinkingBlocksButTheCurrentTurnKeepsThem() throws Exception {
        ArrayNode m = msgs("[{\"role\":\"user\",\"content\":\"a\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"s\"},"
                + "{\"type\":\"tool_use\",\"id\":\"1\",\"name\":\"x\",\"input\":{}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"1\",\"content\":\"r\"}]},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]},"
                + "{\"role\":\"user\",\"content\":\"b\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"s\"},"
                + "{\"type\":\"tool_use\",\"id\":\"2\",\"name\":\"x\",\"input\":{}}]}]");
        ArrayNode out = client(0, false, 0).withoutFinishedThinking(m);
        assertEquals("tool_use", out.get(1).get("content").get(0).get("type").asText());
        assertEquals("thinking", out.get(5).get("content").get(0).get("type").asText());
        assertEquals("thinking", m.get(1).get("content").get(0).get("type").asText());
    }

    @Test
    void parsesResponseAndSendsHeadersAndCacheControl() throws Exception {
        ModelResponse r = client(0, true, 0).complete(req(null, false));
        assertEquals("end_turn", r.stopReason());
        assertEquals("hi", r.content().get(0).get("text").asText());
        assertEquals(10, r.usage().inputTokens());
        assertEquals("k-123|2023-06-01", keys.peek());
        JsonNode b = lastBody();
        assertEquals("claude-test", b.get("model").asText());
        assertEquals(2048, b.get("max_tokens").asInt());
        assertEquals("1h", b.at("/system/1/cache_control/ttl").asText());
        assertEquals("ephemeral", b.at("/tools/1/cache_control/type").asText());
        assertTrue(b.path("tool_choice").isMissingNode(), "no tool_choice by default");
    }

    @Test
    void toolChoiceVariants() throws Exception {
        AnthropicModelClient c = client(0, true, 0);
        c.complete(req("any", false));
        assertEquals("any", lastBody().at("/tool_choice/type").asText());
        c.complete(req("none", true));
        assertEquals("none", lastBody().at("/tool_choice/type").asText());
        assertTrue(lastBody().at("/tool_choice/disable_parallel_tool_use").isMissingNode());
        c.complete(req("b", false));
        assertEquals("tool", lastBody().at("/tool_choice/type").asText());
        assertEquals("b", lastBody().at("/tool_choice/name").asText());
        c.complete(req(null, true));
        assertEquals("auto", lastBody().at("/tool_choice/type").asText());
        assertTrue(lastBody().at("/tool_choice/disable_parallel_tool_use").asBoolean());
    }

    @Test
    void forcedToolChoiceDowngradedWithThinking() throws Exception {
        client(0, true, 2000).complete(req("b", false));
        assertEquals("auto", lastBody().at("/tool_choice/type").asText());
        assertEquals("enabled", lastBody().at("/thinking/type").asText());
    }

    @Test
    void retriesOn429And529And5xxThenSucceeds() throws Exception {
        statuses = new int[]{429, 529, 500, 200};
        ModelResponse r = client(5, true, 0).complete(req(null, false));
        assertEquals(4, calls.get());
        assertEquals("end_turn", r.stopReason());
    }

    @Test
    void honorsRetryAfterHeader() throws Exception {
        statuses = new int[]{429, 200};
        retryAfter = "1";
        long t0 = System.nanoTime();
        client(2, true, 0).complete(req(null, false));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(2, calls.get());
        assertTrue(ms >= 900, "should wait ~1s per retry-after, waited " + ms);
    }

    @Test
    void givesUpAfterMaxRetriesAndDoesNotRetry4xx() throws Exception {
        statuses = new int[]{529};
        ModelException e = assertThrows(ModelException.class, () -> client(2, true, 0).complete(req(null, false)));
        assertTrue(e.getMessage().contains("529"));
        assertEquals(3, calls.get());

        calls.set(0);
        statuses = new int[]{400};
        assertThrows(ModelException.class, () -> client(5, true, 0).complete(req(null, false)));
        assertEquals(1, calls.get());
    }

    @Test
    void preservesAllStopReasons() throws Exception {
        for (String stop : List.of("max_tokens", "pause_turn", "refusal", "stop_sequence", "tool_use")) {
            okBody = "{\"content\":[],\"stop_reason\":\"" + stop + "\",\"usage\":{}}";
            assertEquals(stop, client(0, true, 0).complete(req(null, false)).stopReason());
        }
    }

    @Test
    void ragToolResultsBecomeSearchResultBlocks() throws Exception {
        String chunks = "[{\"filePath\":\"src/A.java\",\"content\":\"class A {}\",\"sourceId\":\"repo_src:src/A.java#2\"}]";
        ArrayNode m = (ArrayNode) mapper.readTree("[{\"role\":\"user\",\"content\":\"q\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search_local_knowledge\",\"input\":{}},"
                + "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"read_file\",\"input\":{}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":"
                + mapper.writeValueAsString(chunks) + "},{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"content\":\"[1]\"}]}]");
        client(0, true, 0).complete(new ModelRequest("p", "", m, List.of(tool("a")), 0));
        JsonNode sent = lastBody().at("/messages/2/content");
        JsonNode sr = sent.get(0).get("content").get(0);
        assertEquals("search_result", sr.get("type").asText());
        assertEquals("repo_src:src/A.java#2", sr.get("source").asText());
        assertEquals("src/A.java", sr.get("title").asText());
        assertEquals("class A {}", sr.at("/content/0/text").asText());
        assertTrue(sr.at("/citations/enabled").asBoolean());
        assertEquals("[1]", sent.get(1).get("content").asText(), "non-RAG results are untouched");
        assertTrue(m.get(2).get("content").get(0).get("content").isTextual(), "stored history is not mutated");

        client(0, false, 0).complete(new ModelRequest("p", "", m, List.of(tool("a")), 0));
        assertTrue(lastBody().at("/messages/2/content/0/content").isTextual());
    }

    @Test
    void unparseableRagResultStaysText() throws Exception {
        ArrayNode m = (ArrayNode) mapper.readTree("[{\"role\":\"user\",\"content\":\"q\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search_local_knowledge\",\"input\":{}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"[]\"}]}]");
        client(0, true, 0).complete(new ModelRequest("p", "", m, List.of(tool("a")), 0));
        assertEquals("[]", lastBody().at("/messages/2/content/0/content").asText());
    }
}

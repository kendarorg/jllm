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

class OpenAiCompatModelClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final Queue<String> bodies = new ConcurrentLinkedQueue<>();
    private final Queue<String> auths = new ConcurrentLinkedQueue<>();
    private final Queue<int[]> noop = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    /** status codes to answer with, in order; last one repeats */
    private volatile int[] statuses = {200};
    private volatile String okBody = "{}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = calls.getAndIncrement();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auths.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            int st = statuses[Math.min(n, statuses.length - 1)];
            byte[] out = (st == 200 ? okBody : "{\"error\":\"boom\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
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

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private OpenAiCompatModelClient client(String key, int retries, boolean completionTokens) {
        return new OpenAiCompatModelClient(new LlmSettings("test", baseUrl(), key, "m1", 777, retries, 10,
                key != null, completionTokens), mapper, 1);
    }

    private ArrayNode messages(String json) throws Exception {
        return (ArrayNode) mapper.readTree(json);
    }

    private HarnessTool tool() {
        return new HarnessTool() {
            public String name() { return "read_file"; }
            public String description() { return "reads"; }
            public JsonNode inputSchema() {
                try { return mapper.readTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
            public String group() { return "Files"; }
            public ToolKind kind() { return ToolKind.READ_ONLY; }
            public String execute(ToolContext ctx, JsonNode input) { return ""; }
        };
    }

    @Test
    void translatesRequest() throws Exception {
        okBody = "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}";
        ArrayNode msgs = messages("""
                [{"role":"user","content":"read a.txt"},
                 {"role":"assistant","content":[{"type":"text","text":"ok"},
                   {"type":"tool_use","id":"tu1","name":"read_file","input":{"path":"a.txt"}}]},
                 {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":[{"type":"text","text":"data"}]},
                   {"type":"text","text":"now summarize"}]}]
                """);
        client("k", 0, true).complete(new ModelRequest("PINNED", "EXTRA", msgs, List.of(tool()), 0));

        JsonNode b = mapper.readTree(bodies.peek());
        assertEquals("m1", b.get("model").asText());
        assertEquals(777, b.get("max_completion_tokens").asInt());
        assertFalse(b.has("max_tokens"));
        assertEquals("Bearer k", auths.peek());
        JsonNode m = b.get("messages");
        assertEquals("system", m.get(0).get("role").asText());
        assertEquals("PINNED\n\nEXTRA", m.get(0).get("content").asText());
        assertEquals("user", m.get(1).get("role").asText());
        JsonNode asst = m.get(2);
        assertEquals("assistant", asst.get("role").asText());
        assertEquals("ok", asst.get("content").asText());
        JsonNode tc = asst.get("tool_calls").get(0);
        assertEquals("tu1", tc.get("id").asText());
        assertEquals("function", tc.get("type").asText());
        assertEquals("read_file", tc.get("function").get("name").asText());
        assertEquals("a.txt", mapper.readTree(tc.get("function").get("arguments").asText()).get("path").asText());
        assertEquals("tool", m.get(3).get("role").asText());
        assertEquals("tu1", m.get(3).get("tool_call_id").asText());
        assertEquals("data", m.get(3).get("content").asText());
        assertEquals("user", m.get(4).get("role").asText());
        assertEquals("now summarize", m.get(4).get("content").asText());
        JsonNode t = b.get("tools").get(0);
        assertEquals("function", t.get("type").asText());
        assertEquals("read_file", t.get("function").get("name").asText());
        assertEquals("object", t.get("function").get("parameters").get("type").asText());
    }

    @Test
    void usesMaxTokensForNonOpenAi() throws Exception {
        okBody = "{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}";
        client(null, 0, false).simpleComplete("sys", "hello", 55);
        JsonNode b = mapper.readTree(bodies.peek());
        assertEquals(55, b.get("max_tokens").asInt());
        assertEquals("null", auths.peek()); // no key => no Authorization header
    }

    @Test
    void translatesToolCallResponse() throws Exception {
        okBody = """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"c1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"x\\"}"}}]},
                  "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":40}}}
                """;
        ModelResponse r = client("k", 0, true).complete(new ModelRequest("p", "", messages("[{\"role\":\"user\",\"content\":\"q\"}]"), List.of(), 0));
        assertEquals("tool_use", r.stopReason());
        assertEquals(1, r.content().size());
        JsonNode tu = r.content().get(0);
        assertEquals("tool_use", tu.get("type").asText());
        assertEquals("c1", tu.get("id").asText());
        assertEquals("x", tu.get("input").get("path").asText());
        assertEquals(new Usage(60, 7, 40, 0), r.usage());
        assertEquals(100, r.usage().contextTokens());
    }

    @Test
    void tolerantParsing() throws Exception {
        // arguments as object, missing id, missing usage, finish_reason stop with tool calls, text + call
        okBody = """
                {"choices":[{"message":{"content":"thinking","tool_calls":[
                  {"function":{"name":"read_file","arguments":{"path":"y"}}},
                  {"function":{"name":"read_file","arguments":""}},
                  {"function":{"name":"read_file","arguments":"{not json"}}]},
                  "finish_reason":"stop"}]}
                """;
        ModelResponse r = client("k", 0, true).complete(new ModelRequest("p", "", messages("[{\"role\":\"user\",\"content\":\"q\"}]"), List.of(), 0));
        assertEquals("tool_use", r.stopReason());
        assertEquals(Usage.ZERO, r.usage());
        assertEquals("text", r.content().get(0).get("type").asText());
        JsonNode a = r.content().get(1), b = r.content().get(2), c = r.content().get(3);
        assertFalse(a.get("id").asText().isBlank());
        assertNotEquals(a.get("id").asText(), b.get("id").asText());
        assertEquals("y", a.get("input").get("path").asText());
        assertTrue(b.get("input").isObject());
        assertEquals(0, b.get("input").size());
        assertEquals("{not json", c.get("input").get("_raw_arguments").asText());
    }

    @Test
    void finishReasons() throws Exception {
        okBody = "{\"choices\":[{\"message\":{\"content\":\"cut\"},\"finish_reason\":\"length\"}]}";
        assertEquals("max_tokens", client("k", 0, true).complete(new ModelRequest("p", "", messages("[]"), List.of(), 0)).stopReason());
        okBody = "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}";
        ModelResponse r = client("k", 0, true).complete(new ModelRequest("p", "", messages("[]"), List.of(), 0));
        assertEquals("end_turn", r.stopReason());
        assertEquals("done", r.content().get(0).get("text").asText());
    }

    @Test
    void cutOffTextToolCallBecomesToolUse() throws Exception {
        String text = "Writing it.\n<tool_call>\n{\"name\": \"write_file\", \"arguments\": {\"path\": \"a.txt\", "
                + "\"content\": \"one\\ntw";
        ModelResponse r = client("k", 0, true).parseResponse(mapper.createObjectNode().set("choices",
                mapper.createArrayNode().add(mapper.createObjectNode().put("finish_reason", "length")
                        .set("message", mapper.createObjectNode().put("content", text)))));
        assertEquals("max_tokens", r.stopReason());
        assertEquals(2, r.content().size());
        assertEquals("Writing it.\n", r.content().get(0).get("text").asText());
        JsonNode tu = r.content().get(1);
        assertEquals("tool_use", tu.get("type").asText());
        assertEquals("write_file", tu.get("name").asText());
        assertEquals("one\ntw", PartialToolCall.field(tu.get("input"), "content"));
        assertEquals("a.txt", PartialToolCall.field(tu.get("input"), "path"));
    }

    @Test
    void closedTextToolCallOrNormalStopStaysText() throws Exception {
        String text = "<tool_call>{\"name\": \"x\"}</tool_call> and more";
        ModelResponse r = client("k", 0, true).parseResponse(mapper.createObjectNode().set("choices",
                mapper.createArrayNode().add(mapper.createObjectNode().put("finish_reason", "length")
                        .set("message", mapper.createObjectNode().put("content", text)))));
        assertEquals(1, r.content().size());
        assertEquals("text", r.content().get(0).get("type").asText());
    }

    @Test
    void retriesOn429And5xxThenSucceeds() {
        statuses = new int[]{429, 503, 200};
        okBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        assertEquals("ok", client("k", 3, true).simpleComplete("s", "u", 10));
        assertEquals(3, calls.get());
    }

    @Test
    void givesUpAfterMaxRetries() {
        statuses = new int[]{500};
        ModelException e = assertThrows(ModelException.class, () -> client("k", 2, true).simpleComplete("s", "u", 10));
        assertTrue(e.getMessage().contains("500"));
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryOn400() {
        statuses = new int[]{400};
        assertThrows(ModelException.class, () -> client("k", 5, true).simpleComplete("s", "u", 10));
        assertEquals(1, calls.get());
    }

    @Test
    void nonJson200FailsAtOnceWithTheBody() {
        okBody = "<html><body>Sign in to the network</body></html>";
        ModelException e = assertThrows(ModelException.class, () -> client("k", 3, true).simpleComplete("s", "u", 10));
        assertTrue(e.getMessage().contains("non-JSON"), e.getMessage());
        assertTrue(e.getMessage().contains("Sign in to the network"), e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void errorInside200IsReported() {
        okBody = "{\"error\":{\"message\":\"Provider returned error\",\"code\":502}}";
        ModelException e = assertThrows(ModelException.class, () -> client("k", 3, true).simpleComplete("s", "u", 10));
        assertTrue(e.getMessage().contains("Provider returned error"), e.getMessage());
        assertTrue(e.getMessage().contains("502"), e.getMessage());
        assertEquals(1, calls.get());
        okBody = "{\"error\":\"rate limited\"}";
        assertTrue(assertThrows(ModelException.class, () -> client("k", 0, true).simpleComplete("s", "u", 10))
                .getMessage().contains("rate limited"));
    }

    @Test
    void emptyChoicesIsAnError() {
        okBody = "{\"id\":\"x\",\"choices\":[]}";
        ModelException e = assertThrows(ModelException.class, () -> client("k", 0, true).simpleComplete("s", "u", 10));
        assertTrue(e.getMessage().contains("no choices"), e.getMessage());
        okBody = "{}";
        assertThrows(ModelException.class, () -> client("k", 0, true).simpleComplete("s", "u", 10));
    }

    @Test
    void contentFilterIsARefusal() throws Exception {
        okBody = "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"content_filter\"}]}";
        ModelResponse r = client("k", 0, true).complete(new ModelRequest("p", "", messages("[]"), List.of(), 0));
        assertEquals("refusal", r.stopReason());
    }

    @Test
    void missingKeyFailsFastWhenRequired() {
        OpenAiCompatModelClient c = new OpenAiCompatModelClient(
                new LlmSettings("openai", baseUrl(), "", "m", 10, 0, 5, true, true), mapper, 1);
        assertThrows(ModelException.class, () -> c.simpleComplete("s", "u", 10));
        assertEquals(0, calls.get());
    }

    // ------------------------------------------------------------ provider selection

    private static LlmSettings settings(String name) {
        return new LlmSettings(name, "http://localhost/v1", "k", "m", 10, 0, 5, true, false);
    }

    @Test
    void providerSelection() {
        assertInstanceOf(AnthropicModelClient.class, ModelClients.from(settings("anthropic"), mapper));
        assertInstanceOf(AnthropicModelClient.class, ModelClients.from(settings("ANTHROPIC"), mapper));
        assertInstanceOf(OpenAiCompatModelClient.class, ModelClients.from(settings("openai"), mapper));
        assertInstanceOf(OpenAiCompatModelClient.class, ModelClients.from(settings("qwen"), mapper));
        assertInstanceOf(OpenAiCompatModelClient.class, ModelClients.from(settings("llamacpp"), mapper));
        assertInstanceOf(OpenAiCompatModelClient.class, ModelClients.ollama("qwen3:8b", mapper));
    }

    @Test
    void ollamaPresetNeedsNoKeyAndUsesTheLocalEndpoint() {
        LlmSettings s = ModelClients.ollamaSettings(ModelClients.OLLAMA_URL, "qwen3:8b");

        assertEquals("http://localhost:11434/v1", s.baseUrl());
        assertEquals("qwen3:8b", s.model());
        assertFalse(s.requireApiKey());
    }
}

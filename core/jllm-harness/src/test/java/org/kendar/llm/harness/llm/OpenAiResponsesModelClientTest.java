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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiResponsesModelClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final Queue<String> bodies = new ConcurrentLinkedQueue<>();
    private volatile String okBody = "{}";
    private volatile String contentType = "application/json";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = okBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", contentType);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiResponsesModelClient client() {
        return new OpenAiResponsesModelClient(new LlmSettings(OpenAiResponsesModelClient.PROVIDER,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "k", "gpt-6.2-sol", 777, 0, 10,
                true, true), mapper, 1);
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

    private static final String REASONING = "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[{\"type\":\"summary_text\","
            + "\"text\":\"look at the file\"}],\"encrypted_content\":\"ENC\"}";

    @Test
    void systemPromptGoesToDeveloperInputNeverInstructions() throws Exception {
        okBody = "{\"status\":\"completed\",\"output\":[]}";
        ArrayNode msgs = (ArrayNode) mapper.readTree("[{\"role\":\"user\",\"content\":\"hi\"}]");
        client().complete(new ModelRequest("PINNED", "POLICY", msgs, List.of(tool()), 0, "auto", true, 0.5,
                ThinkingLevel.MINIMAL));
        JsonNode body = mapper.readTree(bodies.poll());
        assertFalse(body.has("instructions"));
        assertFalse(body.has("temperature"));
        assertFalse(body.has("prompt_cache_key"));
        assertFalse(body.path("store").asBoolean(true));
        assertEquals("reasoning.encrypted_content", body.path("include").path(0).asText());
        assertEquals(777, body.path("max_output_tokens").asInt());
        assertEquals("low", body.path("reasoning").path("effort").asText());
        JsonNode input = body.path("input");
        assertEquals("developer", input.path(0).path("role").asText());
        assertEquals("PINNED", input.path(0).path("content").asText());
        assertEquals("developer", input.path(1).path("role").asText());
        assertEquals("POLICY", input.path(1).path("content").asText());
        assertEquals("user", input.path(2).path("role").asText());
        JsonNode t = body.path("tools").path(0);
        assertEquals("function", t.path("type").asText());
        assertEquals("read_file", t.path("name").asText());
        assertFalse(t.path("strict").asBoolean(true));
        assertFalse(body.path("parallel_tool_calls").asBoolean(true));
    }

    @Test
    void historyReplaysReasoningCallsAndOutputsInOrder() throws Exception {
        okBody = "{\"status\":\"completed\",\"output\":[]}";
        ArrayNode msgs = (ArrayNode) mapper.readTree("[{\"role\":\"user\",\"content\":\"read a\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\",\"openai_reasoning\":"
                + REASONING + "},{\"type\":\"thinking\",\"thinking\":\"claude\",\"signature\":\"s\"},"
                + "{\"type\":\"text\",\"text\":\"reading\"},"
                + "{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"read_file\",\"input\":{\"path\":\"a\"}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"A\","
                + "\"is_error\":true}]}]");
        client().complete(new ModelRequest(null, null, msgs, List.of(tool()), 0));
        JsonNode input = mapper.readTree(bodies.poll()).path("input");
        assertEquals(5, input.size());
        assertEquals("user", input.path(0).path("role").asText());
        assertEquals(mapper.readTree(REASONING), input.path(1));
        assertEquals("assistant", input.path(2).path("role").asText());
        assertEquals("reading", input.path(2).path("content").asText());
        assertEquals("function_call", input.path(3).path("type").asText());
        assertEquals("call_1", input.path(3).path("call_id").asText());
        assertEquals("{\"path\":\"a\"}", input.path(3).path("arguments").asText());
        assertEquals("function_call_output", input.path(4).path("type").asText());
        assertEquals("Error: A", input.path(4).path("output").asText());
    }

    @Test
    void parsesOutputItems() {
        okBody = "{\"status\":\"completed\",\"output\":[" + REASONING + ","
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]},"
                + "{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"b\\\"}\"}],"
                + "\"usage\":{\"input_tokens\":1000,\"input_tokens_details\":{\"cached_tokens\":900},\"output_tokens\":20}}";
        ModelResponse r = client().complete(new ModelRequest(null, null, mapper.createArrayNode(), List.of(), 0));
        assertEquals("tool_use", r.stopReason());
        assertEquals("thinking", r.content().path(0).path("type").asText());
        assertEquals("look at the file", r.content().path(0).path("thinking").asText());
        assertEquals("ENC", r.content().path(0).path(OpenAiResponsesModelClient.REASONING_FIELD)
                .path("encrypted_content").asText());
        assertEquals("ok", r.content().path(1).path("text").asText());
        assertEquals("call_9", r.content().path(2).path("id").asText());
        assertEquals("b", r.content().path(2).path("input").path("path").asText());
        assertEquals(new Usage(100, 20, 900, 0), r.usage());
    }

    @Test
    void incompleteIsMaxTokens() {
        okBody = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":["
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"cut\"}]}]}";
        assertEquals("max_tokens",
                client().complete(new ModelRequest(null, null, mapper.createArrayNode(), List.of(), 0)).stopReason());
    }

    @Test
    void streamsDeltasAndReturnsTheFinalResponse() {
        contentType = "text/event-stream";
        String done = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"Hello\"}]}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}";
        okBody = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
                + "\"delta\":\"Hel\"}\n\n"
                + "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
                + "\"delta\":\"lo\"}\n\n"
                + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":" + done + "}\n\n";
        List<String> deltas = new ArrayList<>();
        ModelResponse r = client().stream(new ModelRequest(null, null, mapper.createArrayNode(), List.of(), 0),
                CancelToken.NONE, new StreamListener() {
                    @Override
                    public void onText(int block, String delta) {
                        deltas.add(delta);
                    }
                });
        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals("Hello", r.content().path(0).path("text").asText());
        assertTrue(bodies.peek().contains("\"stream\":true"));
    }

    @Test
    void providerWiring() {
        assertInstanceOf(OpenAiResponsesModelClient.class,
                ModelClientFactory.create("openaiGpt62", "k", null, null, 1000, mapper));
        LlmSettings s = new ProviderSettings("gpt", "openaiGpt62", null, "k", null, null, null, null, null, null,
                null, null, null, null, null).toLlmSettings();
        assertEquals(OpenAiResponsesModelClient.PROVIDER, s.name());
        assertEquals("https://api.openai.com/v1", s.baseUrl());
        assertEquals("gpt-6.2-sol", s.model());
        assertInstanceOf(OpenAiResponsesModelClient.class, ModelClients.from(s, mapper));
    }

    @Test
    void anthropicNeverSendsGptReasoning() throws Exception {
        AnthropicModelClient a = new AnthropicModelClient(AnthropicSettings.defaults("k"), mapper);
        ArrayNode msgs = (ArrayNode) mapper.readTree("[{\"role\":\"user\",\"content\":\"go\"},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\",\"openai_reasoning\":"
                + REASONING + "}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"c\",\"content\":\"r\"}]}]");
        ArrayNode out = a.withoutFinishedThinking(msgs);
        JsonNode content = out.path(1).path("content");
        assertEquals(1, content.size());
        assertEquals("text", content.path(0).path("type").asText());
        assertTrue(msgs.path(1).path("content").path(0).has(OpenAiResponsesModelClient.REASONING_FIELD));
    }
}

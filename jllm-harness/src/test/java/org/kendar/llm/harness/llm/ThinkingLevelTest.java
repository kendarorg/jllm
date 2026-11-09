package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.text;

class ThinkingLevelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final Queue<String> bodies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int[] statuses = {200};

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = calls.getAndIncrement();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int st = statuses[Math.min(n, statuses.length - 1)];
            String ok = "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}],\"usage\":{}}";
            byte[] out = (st == 200 ? ok : "{\"error\":\"unsupported reasoning_effort\"}")
                    .getBytes(StandardCharsets.UTF_8);
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private JsonNode body(int i) throws Exception {
        return mapper.readTree(new ArrayList<>(bodies).get(i));
    }

    private ModelRequest req() {
        return new ModelRequest("p", "", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("role", "user").put("content", "q")), List.of(), 0);
    }

    private OpenAiCompatModelClient openAi(String name) {
        return new OpenAiCompatModelClient(new LlmSettings(name, url() + "/v1", "k", "m", 100, 0, 10, false, false),
                mapper, 1);
    }

    private HarnessTool tool() {
        return new HarnessTool() {
            public String name() { return "a"; }
            public String description() { return "d"; }
            public JsonNode inputSchema() { return mapper.createObjectNode().put("type", "object"); }
            public String group() { return "g"; }
            public ToolKind kind() { return ToolKind.READ_ONLY; }
            public String execute(ToolContext ctx, JsonNode input) { return ""; }
        };
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.parse("High"));
        assertNull(ThinkingLevel.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> ThinkingLevel.parse("extreme"));
    }

    @Test
    void anthropicMapsLevelToBudgetAndMinimalIsOff() {
        AnthropicModelClient c = new AnthropicModelClient(AnthropicSettings.defaults("k"), mapper);

        JsonNode low = c.buildBody(req().withThinkingLevel(ThinkingLevel.LOW));
        assertEquals("enabled", low.path("thinking").path("type").asText());
        assertEquals(2048, low.path("thinking").path("budget_tokens").asInt());
        assertTrue(low.path("max_tokens").asInt() >= 2048 + 1024);

        assertEquals(16384, c.buildBody(req().withThinkingLevel(ThinkingLevel.HIGH))
                .path("thinking").path("budget_tokens").asInt());
        assertFalse(c.buildBody(req().withThinkingLevel(ThinkingLevel.MINIMAL)).has("thinking"));
        assertFalse(c.buildBody(req()).has("thinking"));
    }

    @Test
    void levelOverridesTheConfiguredAnthropicBudget() {
        AnthropicSettings s = new AnthropicSettings("k", "https://x", "m", 2048, 0, "1h", 4096, 10, false);
        AnthropicModelClient c = new AnthropicModelClient(s, mapper);
        assertEquals(4096, c.buildBody(req()).path("thinking").path("budget_tokens").asInt());
        assertEquals(8192, c.buildBody(req().withThinkingLevel(ThinkingLevel.MEDIUM))
                .path("thinking").path("budget_tokens").asInt());
        assertFalse(c.buildBody(req().withThinkingLevel(ThinkingLevel.MINIMAL)).has("thinking"));
    }

    @Test
    void forcedToolChoiceIsSuppressedWhenThinkingIsOn() {
        AnthropicModelClient c = new AnthropicModelClient(AnthropicSettings.defaults("k"), mapper);
        ModelRequest r = new ModelRequest("p", "", mapper.createArrayNode(), List.of(tool()), 0, "any", false)
                .withThinkingLevel(ThinkingLevel.MEDIUM);
        assertEquals("auto", c.buildBody(r).path("tool_choice").path("type").asText());
        assertEquals("any", c.buildBody(r.withThinkingLevel(ThinkingLevel.MINIMAL))
                .path("tool_choice").path("type").asText());
    }

    @Test
    void openAiCompatSendsReasoningEffortOnlyWhenALevelIsSet() {
        OpenAiCompatModelClient c = openAi("openai");
        assertFalse(c.buildBody(req()).has("reasoning_effort"));
        assertEquals("low", c.buildBody(req().withThinkingLevel(ThinkingLevel.LOW)).path("reasoning_effort").asText());
        assertEquals("high", c.buildBody(req().withThinkingLevel(ThinkingLevel.HIGH)).path("reasoning_effort").asText());
        assertEquals("minimal", c.buildBody(req().withThinkingLevel(ThinkingLevel.MINIMAL))
                .path("reasoning_effort").asText());
        assertEquals("none", openAi("ollama").buildBody(req().withThinkingLevel(ThinkingLevel.MINIMAL))
                .path("reasoning_effort").asText());
    }

    @Test
    void rejectedMinimalStepsDownToLowThenIsDropped() throws Exception {
        statuses = new int[]{400, 400, 200};
        openAi("openai").complete(req().withThinkingLevel(ThinkingLevel.MINIMAL));
        assertEquals(3, calls.get());
        assertEquals("minimal", body(0).path("reasoning_effort").asText());
        assertEquals("low", body(1).path("reasoning_effort").asText());
        assertFalse(body(2).has("reasoning_effort"));
    }

    @Test
    void rejectedEffortIsDroppedAndTheRunContinues() throws Exception {
        statuses = new int[]{400, 200};
        ModelResponse r = openAi("openai").complete(req().withThinkingLevel(ThinkingLevel.HIGH));
        assertEquals("hi", r.content().get(0).get("text").asText());
        assertFalse(body(1).has("reasoning_effort"));
    }

    @Test
    void precedenceIsRequestThenAgentThenProvider() {
        List<ThinkingLevel> seen = new ArrayList<>();
        ScriptedModel inner = new ScriptedModel();
        inner.handler = r -> {
            seen.add(r.thinkingLevel());
            return text("x");
        };
        // provider default LOW (inner), agent MEDIUM (outer)
        ModelClient c = new ThinkingModelClient(new ThinkingModelClient(inner, ThinkingLevel.LOW), ThinkingLevel.MEDIUM);
        ArrayNode m = mapper.createArrayNode();

        c.complete(new ModelRequest("p", "", m, List.of(), 10));
        c.complete(new ModelRequest("p", "", m, List.of(), 10).withThinkingLevel(ThinkingLevel.HIGH));
        new ThinkingModelClient(inner, ThinkingLevel.LOW).complete(new ModelRequest("p", "", m, List.of(), 10));

        assertEquals(List.of(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.LOW), seen);
    }

    @Test
    void providerCatalogAppliesTheProviderDefault() throws Exception {
        ProviderSettings p = new ProviderSettings("q", "llamacpp", url() + "/v1", null, "m", null, 0, null, null, null,
                null, null, null, null, ThinkingLevel.LOW);
        new ProviderCatalog(List.of(p), mapper, 4096).client("q").complete(req());
        assertEquals("low", body(0).path("reasoning_effort").asText());
    }
}

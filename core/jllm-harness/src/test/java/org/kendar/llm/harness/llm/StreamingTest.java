package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Streamed responses are assembled into exactly what a non-streamed call returns. */
class StreamingTest {

    private static final ObjectMapper M = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    /** Answers a request; gets the exchange and the request body. */
    private volatile Handler handler;

    interface Handler {
        void handle(HttpExchange ex, JsonNode body, int call) throws Exception;
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        com.sun.net.httpserver.HttpHandler h = ex -> {
            int n = calls.getAndIncrement();
            String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(b);
            try {
                handler.handle(ex, M.readTree(b), n);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                ex.close();
            }
        };
        server.createContext("/v1/messages", h);
        server.createContext("/v1/chat/completions", h);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AnthropicModelClient anthropic() {
        return new AnthropicModelClient(new AnthropicSettings("k", url(), "m", 1024, 2, "1h", 0, 10, false), M, 1);
    }

    private OpenAiCompatModelClient openai() {
        return new OpenAiCompatModelClient(new LlmSettings("openai", url() + "/v1", "k", "m", 1024, 2, 10, true, false),
                M, 1);
    }

    private static ModelRequest request() {
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", "hi");
        return new ModelRequest("sys", "", msgs, List.of(), 1024);
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
    }

    /** Sends events one by one, flushing each; {@code between} runs after each event (e.g. to wait). */
    private static void sse(HttpExchange ex, List<String> events, Consumer<Integer> between) throws IOException {
        ex.getResponseHeaders().add("content-type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        for (int i = 0; i < events.size(); i++) {
            os.write((events.get(i) + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            between.accept(i);
        }
    }

    private static String ev(String type, String json) {
        return "event: " + type + "\ndata: " + json;
    }

    // ---------------------------------------------------------------- Anthropic

    private static final String A_MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":["
            + "{\"type\":\"thinking\",\"thinking\":\"Let me think about it.\",\"signature\":\"sig-abc==\"},"
            + "{\"type\":\"text\",\"text\":\"Hello, world. Calling a tool.\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"write_file\",\"input\":{\"path\":\"a.txt\","
            + "\"content\":\"line1\\nline2\",\"n\":3}}],"
            + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":12,\"output_tokens\":40,\"cache_read_input_tokens\":5,"
            + "\"cache_creation_input_tokens\":1}}";

    private static List<String> anthropicEvents(boolean complete) {
        List<String> e = new ArrayList<>();
        e.add(ev("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":"
                + "{\"input_tokens\":12,\"output_tokens\":1,\"cache_read_input_tokens\":5,"
                + "\"cache_creation_input_tokens\":1}}}"));
        e.add(ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                + "{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        e.add(ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                + "{\"type\":\"thinking_delta\",\"thinking\":\"Let me think \"}}"));
        e.add(ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                + "{\"type\":\"thinking_delta\",\"thinking\":\"about it.\"}}"));
        e.add(ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                + "{\"type\":\"signature_delta\",\"signature\":\"sig-abc==\"}}"));
        e.add(ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
        e.add("event: ping\ndata: {\"type\":\"ping\"}");
        e.add(ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":"
                + "{\"type\":\"text\",\"text\":\"\"}}"));
        for (String part : new String[]{"Hello", ", world.", " Calling a tool."}) {
            e.add(ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                    + "{\"type\":\"text_delta\",\"text\":\"" + part + "\"}}"));
        }
        e.add(ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"));
        e.add(ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"write_file\",\"input\":{}}}"));
        for (String part : new String[]{"{\\\"path\\\": \\\"a.t", "xt\\\", \\\"content\\\": \\\"line1\\\\nli",
                "ne2\\\", \\\"n\\\": 3}"}) {
            e.add(ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":"
                    + "{\"type\":\"input_json_delta\",\"partial_json\":\"" + part + "\"}}"));
        }
        if (!complete) return e;
        e.add(ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}"));
        e.add(ev("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\","
                + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":40}}"));
        e.add(ev("message_stop", "{\"type\":\"message_stop\"}"));
        return e;
    }

    /** Records the deltas a listener gets. */
    static final class Recorder implements StreamListener {
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder args = new StringBuilder();
        int resets;
        int deltas;

        @Override
        public void onText(int block, String delta) {
            text.append(delta);
            deltas++;
        }

        @Override
        public void onThinking(int block, String delta) {
            thinking.append(delta);
            deltas++;
        }

        @Override
        public void onToolCall(int block, String id, String name, String argsDelta) {
            args.append(argsDelta);
        }

        @Override
        public void onReset() {
            resets++;
            text.setLength(0);
            thinking.setLength(0);
            args.setLength(0);
        }
    }

    @Test
    void anthropicStreamedEqualsNonStreamed() {
        handler = (ex, body, n) -> {
            if (body.path("stream").asBoolean(false)) sse(ex, anthropicEvents(true), i -> { });
            else json(ex, A_MESSAGE);
        };
        AnthropicModelClient c = anthropic();
        ModelResponse plain = c.complete(request());
        Recorder r = new Recorder();
        ModelResponse streamed = c.stream(request(), CancelToken.NONE, r);

        assertEquals(plain.content(), streamed.content());
        assertEquals(plain.content().toString(), streamed.content().toString()); // same key order too
        assertEquals(plain.stopReason(), streamed.stopReason());
        assertEquals(plain.usage(), streamed.usage());
        assertEquals("Hello, world. Calling a tool.", r.text.toString());
        assertEquals("Let me think about it.", r.thinking.toString());
        assertTrue(r.args.toString().contains("line1"));
        assertFalse(bodies.get(0).contains("\"stream\""));
        assertTrue(bodies.get(1).contains("\"stream\":true"));
    }

    @Test
    void anthropicCutOffToolCallKeepsTheRawArguments() {
        handler = (ex, body, n) -> {
            List<String> e = anthropicEvents(false);
            e.add(ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}"));
            e.add(ev("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},"
                    + "\"usage\":{\"output_tokens\":1024}}"));
            e.add(ev("message_stop", "{\"type\":\"message_stop\"}"));
            // drop the last argument piece: the JSON is cut
            e.remove(e.size() - 4);
            sse(ex, e, i -> { });
        };
        ModelResponse r = anthropic().stream(request(), CancelToken.NONE, new Recorder());
        assertEquals("max_tokens", r.stopReason());
        JsonNode cut = r.content().get(2);
        assertTrue(cut.path("input").has(PartialToolCall.RAW), cut.toString());
        assertEquals("line1\nli", PartialToolCall.field(cut.path("input"), "content"));
        assertEquals(1024, r.usage().outputTokens());
    }

    @Test
    void anthropicPauseTurnAndRefusalComeThrough() {
        for (String stop : new String[]{"pause_turn", "refusal"}) {
            handler = (ex, body, n) -> sse(ex, List.of(
                    ev("message_start", "{\"type\":\"message_start\",\"message\":{\"content\":[],\"usage\":{}}}"),
                    ev("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stop + "\"}}"),
                    ev("message_stop", "{\"type\":\"message_stop\"}")), i -> { });
            assertEquals(stop, anthropic().stream(request(), CancelToken.NONE, new Recorder()).stopReason());
        }
    }

    @Test
    void anErrorMidStreamResetsAndRetriesAsAWhole() {
        handler = (ex, body, n) -> {
            if (n == 0) {
                List<String> e = anthropicEvents(true).subList(0, 10); // through some text, then the connection drops
                sse(ex, e, i -> { });
            } else {
                sse(ex, anthropicEvents(true), i -> { });
            }
        };
        Recorder r = new Recorder();
        List<Integer> retries = new ArrayList<>();
        CancelToken token = new CancelToken();
        token.onRetry((status, attempt, max, delay) -> retries.add(attempt));
        ModelResponse resp = anthropic().stream(request(), token, r);
        assertEquals(1, r.resets);
        assertEquals(List.of(1), retries);
        assertEquals("Hello, world. Calling a tool.", r.text.toString());
        assertEquals(3, resp.content().size());
    }

    @Test
    void anErrorEventThatIsNotRetryableFails() {
        handler = (ex, body, n) -> sse(ex, List.of(
                ev("message_start", "{\"type\":\"message_start\",\"message\":{\"content\":[],\"usage\":{}}}"),
                ev("error", "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}")),
                i -> { });
        ModelException e = assertThrows(ModelException.class,
                () -> anthropic().stream(request(), CancelToken.NONE, new Recorder()));
        assertTrue(e.getMessage().contains("invalid_request_error: bad"), e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void cancelMidStreamAborts() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        handler = (ex, body, n) -> sse(ex, anthropicEvents(true), i -> {
            if (i == 9) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        CancelToken token = new CancelToken();
        StreamListener cancelOnText = new StreamListener() {
            @Override
            public void onText(int block, String delta) {
                token.cancel();
            }
        };
        try {
            assertThrows(ModelCancelledException.class, () -> anthropic().stream(request(), token, cancelOnText));
        } finally {
            release.countDown();
        }
        assertEquals(1, calls.get());
    }

    @Test
    void streamingOffMakesAPlainCall() {
        handler = (ex, body, n) -> json(ex, A_MESSAGE);
        anthropic().streaming(false).stream(request(), CancelToken.NONE, new Recorder());
        assertFalse(bodies.get(0).contains("\"stream\""));
    }

    // ---------------------------------------------------------------- OpenAI-compatible

    private static final String O_PLAIN = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Sure, "
            + "doing it.\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"bash\","
            + "\"arguments\":\"{\\\"command\\\":\\\"ls -la\\\"}\"}},{\"id\":\"call_2\",\"type\":\"function\","
            + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"x\\\"}\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":4}}}";

    private static List<String> openaiChunks(boolean usage) {
        List<String> c = new ArrayList<>();
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Sure, \"}}]}");
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"hmm\"}}]}");
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"doing it.\"}}]}");
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"bash\",\"arguments\":\"\"}}]}}]}");
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"{\\\"command\\\":\"}}]}}]}");
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_2\","
                + "\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"x\\\"}\"}}]}}]}");
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                + "{\"arguments\":\"\\\"ls -la\\\"}\"}}]}}]}");
        c.add("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        if (usage) {
            c.add("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":4}}}");
        }
        c.add("data: [DONE]");
        return c;
    }

    @Test
    void openAiStreamedEqualsNonStreamed() {
        handler = (ex, body, n) -> {
            if (body.path("stream").asBoolean(false)) {
                assertTrue(body.path("stream_options").path("include_usage").asBoolean());
                sse(ex, openaiChunks(true), i -> { });
            } else {
                json(ex, O_PLAIN);
            }
        };
        OpenAiCompatModelClient c = openai();
        ModelResponse plain = c.complete(request());
        Recorder r = new Recorder();
        ModelResponse streamed = c.stream(request(), CancelToken.NONE, r);
        assertEquals(plain.content().toString(), streamed.content().toString());
        assertEquals(plain.stopReason(), streamed.stopReason());
        assertEquals(plain.usage(), streamed.usage());
        assertEquals("Sure, doing it.", r.text.toString());
        assertEquals("hmm", r.thinking.toString());
    }

    @Test
    void openAiStreamWithoutUsageLeavesItEmpty() {
        handler = (ex, body, n) -> sse(ex, openaiChunks(false), i -> { });
        ModelResponse r = openai().stream(request(), CancelToken.NONE, new Recorder());
        assertEquals(Usage.ZERO, r.usage());
        assertEquals("tool_use", r.stopReason());
    }

    @Test
    void openAiLengthStopIsMaxTokens() {
        handler = (ex, body, n) -> sse(ex, List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}",
                "data: [DONE]"), i -> { });
        assertEquals("max_tokens", openai().stream(request(), CancelToken.NONE, new Recorder()).stopReason());
    }

    @Test
    void aServerRejectingStreamOptionsIsRetriedWithoutThem() {
        handler = (ex, body, n) -> {
            if (body.has("stream_options")) {
                byte[] out = "{\"error\":\"unknown field stream_options\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(400, out.length);
                ex.getResponseBody().write(out);
            } else {
                sse(ex, openaiChunks(false), i -> { });
            }
        };
        assertEquals("tool_use", openai().stream(request(), CancelToken.NONE, new Recorder()).stopReason());
        assertEquals(2, calls.get());
    }

    // ---------------------------------------------------------------- wrappers

    /** A client that emits one delta and then fails, or answers without deltas. */
    static class Stub implements ModelClient {
        final boolean deltaThenFail;
        int streams;
        ModelRequest last;

        Stub(boolean deltaThenFail) {
            this.deltaThenFail = deltaThenFail;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            throw new AssertionError("complete must not be used");
        }

        @Override
        public ModelResponse stream(ModelRequest request, CancelToken cancel, StreamListener listener) {
            streams++;
            last = request;
            if (deltaThenFail) {
                listener.onText(0, "partial");
                throw new ModelException("broken");
            }
            ArrayNode c = M.createArrayNode();
            c.addObject().put("type", "text").put("text", "ok");
            return new ModelResponse(c, "end_turn", Usage.ZERO);
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            return "";
        }
    }

    @Test
    void fallbackOnlyBeforeTheFirstDelta() {
        Stub broken = new Stub(true);
        Stub good = new Stub(false);
        FallbackModelClient f = new FallbackModelClient(List.of(new FallbackModelClient.Candidate("a", () -> broken),
                new FallbackModelClient.Candidate("b", () -> good)));
        assertThrows(ModelException.class, () -> f.stream(request(), CancelToken.NONE, new Recorder()));
        assertEquals(0, good.streams);

        ModelClient failsEarly = new Stub(false) {
            @Override
            public ModelResponse stream(ModelRequest request, CancelToken cancel, StreamListener listener) {
                throw new ModelException("down");
            }
        };
        FallbackModelClient g = new FallbackModelClient(List.of(new FallbackModelClient.Candidate("a", () -> failsEarly),
                new FallbackModelClient.Candidate("b", () -> good)));
        assertEquals("ok", g.stream(request(), CancelToken.NONE, new Recorder()).content().get(0).path("text").asText());
        assertEquals(1, good.streams);
    }

    @Test
    void wrappersForwardStream() {
        Stub s = new Stub(false);
        new TemperatureModelClient(s, 0.5).stream(request(), CancelToken.NONE, StreamListener.NONE);
        assertEquals(0.5, s.last.temperature());
        new ThinkingModelClient(s, ThinkingLevel.HIGH).stream(request(), CancelToken.NONE, StreamListener.NONE);
        assertEquals(ThinkingLevel.HIGH, s.last.thinkingLevel());
        new LimitedModelClient(s, 1).stream(request(), CancelToken.NONE, StreamListener.NONE);
        new ModelSwitch(s, "x", null).stream(request(), CancelToken.NONE, StreamListener.NONE);
        new FallbackModelClient(List.of(new FallbackModelClient.Candidate("x", () -> s)))
                .stream(request(), CancelToken.NONE, StreamListener.NONE);
        assertEquals(5, s.streams);
    }
}

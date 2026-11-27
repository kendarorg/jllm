package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * An OpenAI compatible {@code /v1/chat/completions} endpoint, streamed or not, that answers from the last message:
 * <ul>
 *   <li>a tool result: {@code tool said: <result>};</li>
 *   <li>a user text with {@code PLEASE WRITE}: a {@code write_file} call of {@code out.txt}
 *   ({@code PLEASE WRITE TO <path>}: of that path);</li>
 *   <li>a user text with {@code SLOW}: waits for {@link #release()}, then {@code slow done};</li>
 *   <li>any other user text: {@code echo: <text>}; a request without tools (titles): {@code A title}.</li>
 * </ul>
 */
final class FakeOpenAiServer implements AutoCloseable {

    static final String WRITTEN = "hello from the model";
    /** {@code PLEASE WRITE TO <path>}: the write_file call targets the path, the rest of the text. */
    static final String WRITE_TO = "PLEASE WRITE TO ";

    private static final ObjectMapper M = new ObjectMapper();

    private final HttpServer server;
    /** Request bodies, in order. */
    final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    /** Authorization headers, in order. */
    final List<String> auth = Collections.synchronizedList(new ArrayList<>());
    /** Counted down when a SLOW request arrives. */
    volatile CountDownLatch slowArrived = new CountDownLatch(1);
    private volatile CountDownLatch slowRelease = new CountDownLatch(1);

    FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-openai");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Lets the waiting SLOW request answer. */
    void release() {
        slowRelease.countDown();
    }

    /** Agent requests (those with tools), in order. */
    List<JsonNode> agentRequests() {
        synchronized (requests) {
            return requests.stream().filter(r -> r.path("tools").size() > 0).toList();
        }
    }

    @Override
    public void close() {
        release();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            JsonNode body = M.readTree(ex.getRequestBody());
            requests.add(body);
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            ObjectNode message = answer(body);
            if (body.path("stream").asBoolean(false)) stream(ex, message);
            else json(ex, message);
        }
    }

    private ObjectNode answer(JsonNode body) {
        ObjectNode msg = M.createObjectNode().put("role", "assistant");
        if (body.path("tools").isEmpty()) return msg.put("content", "A title");
        JsonNode messages = body.path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        String text = text(last.path("content"));
        if (last.path("role").asText().equals("tool")) return msg.put("content", "tool said: " + text);
        if (text.contains("PLEASE WRITE")) {
            int to = text.indexOf(WRITE_TO);
            String path = to >= 0 ? text.substring(to + WRITE_TO.length()).strip() : "out.txt";
            ArrayNode calls = msg.putArray("tool_calls");
            ObjectNode call = calls.addObject().put("id", "call_" + requests.size()).put("type", "function");
            call.putObject("function").put("name", "write_file").put("arguments",
                    M.createObjectNode().put("path", path).put("content", WRITTEN).toString());
            return msg;
        }
        if (text.contains("SLOW")) {
            slowArrived.countDown();
            try {
                slowRelease.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            slowRelease = new CountDownLatch(1);
            slowArrived = new CountDownLatch(1);
            return msg.put("content", "slow done");
        }
        return msg.put("content", "echo: " + text.strip());
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) sb.append(part.path("text").asText());
        return sb.toString();
    }

    private static void json(HttpExchange ex, ObjectNode message) throws IOException {
        ObjectNode r = M.createObjectNode().put("id", "r1").put("model", "fake");
        r.putArray("choices").addObject().put("index", 0).put("finish_reason", finish(message))
                .set("message", message);
        r.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15);
        byte[] out = r.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
    }

    private static void stream(HttpExchange ex, ObjectNode message) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        ObjectNode delta = M.createObjectNode().put("role", "assistant");
        if (message.has("content")) delta.put("content", message.get("content").asText());
        if (message.has("tool_calls")) {
            ArrayNode calls = delta.putArray("tool_calls");
            int i = 0;
            for (JsonNode c : message.get("tool_calls")) {
                ObjectNode copy = c.deepCopy();
                calls.add(copy.put("index", i++));
            }
        }
        ObjectNode first = M.createObjectNode();
        first.putArray("choices").addObject().put("index", 0).set("delta", delta);
        ObjectNode end = M.createObjectNode();
        end.putArray("choices").addObject().put("index", 0).put("finish_reason", finish(message))
                .putObject("delta");
        ObjectNode usage = M.createObjectNode();
        usage.putArray("choices");
        usage.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15);
        for (JsonNode chunk : List.of(first, end, usage)) {
            os.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String finish(ObjectNode message) {
        return message.has("tool_calls") ? "tool_calls" : "stop";
    }
}

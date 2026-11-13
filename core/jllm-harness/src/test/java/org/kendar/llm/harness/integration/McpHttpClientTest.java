package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpHttpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger initializes = new AtomicInteger();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    /** SSE body for tools/call; %ID% is replaced by the request id. */
    private volatile String callSse;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        JsonNode req = MAPPER.readTree(ex.getRequestBody());
        String method = req.path("method").asText();
        String id = req.path("id").toString();
        String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (method.equals("initialize")) {
            String s = "s" + initializes.incrementAndGet();
            sessions.add(s);
            ex.getResponseHeaders().add("Mcp-Session-Id", s);
            reply(ex, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
        } else if (session == null || !sessions.contains(session)) {
            reply(ex, 404, "text/plain", "unknown session");
        } else if (method.startsWith("notifications/")) {
            reply(ex, 202, "text/plain", "");
        } else if (method.equals("tools/list")) {
            reply(ex, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"tools\":[{\"name\":\"echo\"}]}}");
        } else if (callSse != null) {
            reply(ex, 200, "text/event-stream", callSse.replace("%ID%", id));
        } else {
            reply(ex, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok-" + session + "\"}]}}");
        }
    }

    private static void reply(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asText();
    }

    @Test
    void expiredSessionIsInitializedAgainOnce() throws Exception {
        McpHttpClient client = new McpHttpClient(http, MAPPER, url, Map.of());
        assertEquals(1, client.listTools().size());
        assertEquals("ok-s1", text(client.callTool("echo", null)));

        sessions.clear(); // the server forgot every session: next call gets 404
        assertEquals("ok-s2", text(client.callTool("echo", null)));
        assertEquals(2, initializes.get());
        assertNull(client.unavailableReason());
    }

    @Test
    void lostServerMakesTheClientUnavailableAndReportsItOnce() throws Exception {
        List<String> reasons = new CopyOnWriteArrayList<>();
        McpHttpClient client = new McpHttpClient(http, MAPPER, url, Map.of()).onUnavailable(reasons::add);
        client.listTools();
        server.stop(0);

        assertThrows(McpUnavailableException.class, () -> client.callTool("echo", null));
        assertThrows(McpUnavailableException.class, () -> client.callTool("echo", null));
        assertEquals(1, reasons.size());
        assertNotNull(client.unavailableReason());
    }

    @Test
    void serverThatNeverAnsweredIsADiscoveryFailureNotALostOne() {
        List<String> reasons = new CopyOnWriteArrayList<>();
        McpHttpClient client = new McpHttpClient(http, MAPPER, "http://127.0.0.1:1/mcp", Map.of())
                .onUnavailable(reasons::add);
        IOException e = assertThrows(IOException.class, client::listTools);
        assertFalse(e instanceof McpUnavailableException);
        assertTrue(reasons.isEmpty());
    }

    @Test
    void sseParserSkipsNotificationsOtherIdsAndNonJsonData() throws Exception {
        callSse = ": comment\n\n"
                + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":%ID%,\"method\":\"sampling/createMessage\",\"params\":{}}\n\n"
                + "data: not json at all\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":999999,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"wrong\"}]}}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":%ID%,\r\n"
                + "data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"right\"}]}}\r\n\r\n";
        McpHttpClient client = new McpHttpClient(http, MAPPER, url, Map.of());
        assertEquals("right", text(client.callTool("echo", null)));
    }

    @Test
    void sseWithoutTheResponseFails() {
        callSse = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n";
        McpHttpClient client = new McpHttpClient(http, MAPPER, url, Map.of());
        IOException e = assertThrows(IOException.class, () -> client.callTool("echo", null));
        assertTrue(e.getMessage().contains("no response"), e.getMessage());
    }
}

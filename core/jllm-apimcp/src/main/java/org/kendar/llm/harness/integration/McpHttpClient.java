package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client over streamable HTTP: initialize, tools/list and tools/call.
 * Each principal gets its own MCP session (its own {@code Mcp-Session-Id}), so one user's session is never sent
 * with another user's call.
 */
public final class McpHttpClient implements McpClient {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    /** Session key of calls made without a principal. */
    static final String NO_PRINCIPAL = "";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI url;
    private final Map<String, String> headers;
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();
    private final Set<String> initialized = ConcurrentHashMap.newKeySet();
    private volatile org.kendar.llm.harness.auth.AuthProvider auth;

    /** Adds the provider's headers (OAuth bearer) to every request; a 401 triggers one retry with a fresh token. */
    public McpHttpClient authProvider(org.kendar.llm.harness.auth.AuthProvider provider) {
        this.auth = provider;
        return this;
    }

    public McpHttpClient(HttpClient http, ObjectMapper mapper, String url, Map<String, String> headers) {
        this.http = http;
        this.mapper = mapper;
        this.url = URI.create(url);
        this.headers = headers;
    }

    @Override
    public void initialize() throws IOException, InterruptedException {
        initialize(NO_PRINCIPAL, Map.of());
    }

    private synchronized void initialize(String key, Map<String, String> extra) throws IOException, InterruptedException {
        if (initialized.contains(key)) return;
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "jllm-harness").put("version", "1");
        post(request("initialize", params), true, key, extra);
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0").put("method", "notifications/initialized");
        post(note, false, key, extra);
        initialized.add(key);
    }

    @Override
    public List<JsonNode> listTools() throws IOException, InterruptedException {
        initialize();
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = post(request("tools/list", params), true, NO_PRINCIPAL, Map.of());
            result.path("tools").forEach(tools::add);
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null);
        return tools;
    }

    @Override
    public JsonNode callTool(String name, JsonNode arguments) throws IOException, InterruptedException {
        return callTool(name, arguments, NO_PRINCIPAL, Map.of());
    }

    @Override
    public JsonNode callTool(String name, JsonNode arguments, String principalId, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        String key = principalId == null ? NO_PRINCIPAL : principalId;
        initialize(key, extraHeaders);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments);
        return post(request("tools/call", params), true, key, extraHeaders);
    }

    private ObjectNode request(String method, JsonNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", method);
        req.set("params", params);
        return req;
    }

    private JsonNode post(ObjectNode body, boolean expectResult, String key, Map<String, String> extra)
            throws IOException, InterruptedException {
        HttpResponse<String> resp = send(body, key, extra);
        if (resp.statusCode() == 401 && auth != null) {
            auth.onUnauthorized();
            resp = send(body, key, extra);
        }
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionIds.put(key, id));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("MCP " + url + " answered HTTP " + resp.statusCode() + ": " + resp.body());
        }
        if (!expectResult) return null;
        JsonNode message = parse(resp, body.get("id").asLong());
        if (message.hasNonNull("error")) {
            JsonNode err = message.get("error");
            throw new IOException("MCP error: " + err.path("message").asText(err.toString()));
        }
        return message.path("result");
    }

    private HttpResponse<String> send(ObjectNode body, String key, Map<String, String> extra)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        String session = sessionIds.get(key);
        if (session != null) b.header("Mcp-Session-Id", session);
        headers.forEach(b::header);
        extra.forEach(b::header);
        if (auth != null) {
            try {
                auth.headers().forEach(b::header);
            } catch (IllegalStateException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Reads a plain JSON body, or the matching message out of an SSE stream. */
    private JsonNode parse(HttpResponse<String> resp, long id) throws IOException {
        String type = resp.headers().firstValue("Content-Type").orElse("");
        String text = resp.body();
        if (!type.contains("text/event-stream")) return mapper.readTree(text);
        for (String event : text.split("\r?\n\r?\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\r?\n")) {
                if (line.startsWith("data:")) data.append(line.substring(5).stripLeading());
            }
            if (data.isEmpty()) continue;
            JsonNode node = mapper.readTree(data.toString());
            if (node.path("id").asLong(-1) == id) return node;
        }
        throw new IOException("MCP " + url + " sent no response for request " + id);
    }
}

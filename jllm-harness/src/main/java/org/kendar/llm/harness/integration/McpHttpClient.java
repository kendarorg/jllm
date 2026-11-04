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
import java.util.concurrent.atomic.AtomicLong;

/** Minimal MCP client over streamable HTTP: initialize, tools/list and tools/call. */
public final class McpHttpClient {

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI url;
    private final Map<String, String> headers;
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;
    private volatile boolean initialized;

    public McpHttpClient(HttpClient http, ObjectMapper mapper, String url, Map<String, String> headers) {
        this.http = http;
        this.mapper = mapper;
        this.url = URI.create(url);
        this.headers = headers;
    }

    public synchronized void initialize() throws IOException, InterruptedException {
        if (initialized) return;
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "jllm-harness").put("version", "1");
        post(request("initialize", params), true);
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0").put("method", "notifications/initialized");
        post(note, false);
        initialized = true;
    }

    /** @return the entries of tools/list, following pagination */
    public List<JsonNode> listTools() throws IOException, InterruptedException {
        initialize();
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = post(request("tools/list", params), true);
            result.path("tools").forEach(tools::add);
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null);
        return tools;
    }

    /** @return the result object of tools/call */
    public JsonNode callTool(String name, JsonNode arguments) throws IOException, InterruptedException {
        initialize();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments);
        return post(request("tools/call", params), true);
    }

    private ObjectNode request(String method, JsonNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", method);
        req.set("params", params);
        return req;
    }

    private JsonNode post(ObjectNode body, boolean expectResult) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        headers.forEach(b::header);
        HttpResponse<String> resp = http.send(
                b.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
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

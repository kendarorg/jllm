package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal MCP client over streamable HTTP: initialize, tools/list and tools/call.
 * Each principal gets its own MCP session (its own {@code Mcp-Session-Id}), so one user's session is never sent
 * with another user's call.
 * <p>
 * A 404 (expired session) or a connection error makes the client drop the session, initialize again and repeat the
 * request once. When that fails too, a client that had been working becomes unavailable: it reports the reason once
 * to {@link #onUnavailable(Consumer)} and every call throws {@link McpUnavailableException}.
 */
public final class McpHttpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpHttpClient.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";
    /** Session key of calls made without a principal. */
    static final String NO_PRINCIPAL = "";
    /** Most principal sessions kept; the least recently used beyond it is closed. */
    static final int MAX_SESSIONS = 256;
    /** A principal session unused this long is closed. */
    static final long IDLE_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(15);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI url;
    private final Map<String, String> headers;
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();
    private final Set<String> initialized = ConcurrentHashMap.newKeySet();
    /** Last use of each principal session, in access order (oldest first). Guarded by itself. */
    private final java.util.LinkedHashMap<String, Long> lastUse = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private volatile java.util.function.LongSupplier nanoClock = System::nanoTime;
    private final AtomicReference<String> unavailable = new AtomicReference<>();
    private volatile org.kendar.llm.harness.auth.AuthProvider auth;
    private volatile Consumer<String> onUnavailable = reason -> { };
    /** Whether an initialize ever succeeded: a server that never answered is a discovery failure, not a lost one. */
    private volatile boolean connected;

    /** The connection was lost or the session expired: the request can be repeated after a new initialize. */
    private static final class SessionLost extends IOException {
        final String session;

        SessionLost(String message, String session, Throwable cause) {
            super(message, cause);
            this.session = session;
        }
    }

    @FunctionalInterface
    private interface Step {
        JsonNode run() throws IOException, InterruptedException;
    }

    /** Adds the provider's headers (OAuth bearer) to every request; a 401 triggers one retry with a fresh token. */
    public McpHttpClient authProvider(org.kendar.llm.harness.auth.AuthProvider provider) {
        this.auth = provider;
        return this;
    }

    /**
     * Called once, with the reason, when the server is lost for good (re-initializing did not help). The source of
     * this client should then be disabled. Not called while the client never connected (discovery fails instead).
     * The callback runs on the calling thread, outside the locks of this client.
     */
    public McpHttpClient onUnavailable(Consumer<String> callback) {
        this.onUnavailable = callback == null ? reason -> { } : callback;
        return this;
    }

    /** Why the client gave up, null while it is usable. */
    public String unavailableReason() {
        return unavailable.get();
    }

    public McpHttpClient(HttpClient http, ObjectMapper mapper, String url, Map<String, String> headers) {
        this.http = http;
        this.mapper = mapper;
        this.url = URI.create(url);
        this.headers = headers;
    }

    @Override
    public void initialize() throws IOException, InterruptedException {
        withSession(NO_PRINCIPAL, Map.of(), () -> null);
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
        connected = true;
    }

    /** Drops the session of {@code key} unless another thread already replaced the stale one. */
    private synchronized void forget(String key, String stale) {
        String current = sessionIds.get(key);
        if (current != null && !current.equals(stale)) return;
        sessionIds.remove(key);
        initialized.remove(key);
    }

    /** Test seam: the clock of the idle sessions. */
    McpHttpClient nanoClock(java.util.function.LongSupplier clock) {
        this.nanoClock = clock;
        return this;
    }

    /** Number of open principal sessions (tests, status). */
    public int principalSessions() {
        synchronized (lastUse) {
            return lastUse.size();
        }
    }

    /** Marks the session of {@code key} used, and closes the idle ones and the oldest beyond {@link #MAX_SESSIONS}. */
    private void touch(String key) {
        if (NO_PRINCIPAL.equals(key)) return;
        List<String> evict = new ArrayList<>();
        long now = nanoClock.getAsLong();
        synchronized (lastUse) {
            lastUse.put(key, now);
            var it = lastUse.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                boolean idle = now - e.getValue() > IDLE_NANOS;
                boolean over = lastUse.size() - evict.size() > MAX_SESSIONS;
                if (e.getKey().equals(key) || !(idle || over)) continue;
                evict.add(e.getKey());
                it.remove();
            }
        }
        evict.forEach(this::endSession);
    }

    @Override
    public void closeSession(String principalId) {
        String key = principalId == null ? NO_PRINCIPAL : principalId;
        synchronized (lastUse) {
            lastUse.remove(key);
        }
        endSession(key);
    }

    /** Forgets the session of {@code key} and tells the server (DELETE, best effort). */
    private void endSession(String key) {
        String session;
        synchronized (this) {
            session = sessionIds.remove(key);
            initialized.remove(key);
        }
        if (session == null) return;
        try {
            http.sendAsync(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).header("Mcp-Session-Id", session)
                    .DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException e) {
            log.debug("MCP {}: session close failed: {}", url, e.toString());
        }
    }

    /** Runs {@code step} in an initialized session; a lost session is initialized again and the step repeated once. */
    private JsonNode withSession(String key, Map<String, String> extra, Step step)
            throws IOException, InterruptedException {
        String reason = unavailable.get();
        if (reason != null) throw new McpUnavailableException("MCP server " + url + " is unavailable: " + reason);
        touch(key);
        try {
            initialize(key, extra);
            return step.run();
        } catch (SessionLost first) {
            log.warn("MCP {}: {}; initializing a new session", url, first.getMessage());
            forget(key, first.session);
            try {
                initialize(key, extra);
                return step.run();
            } catch (SessionLost second) {
                if (!connected) throw new IOException(second.getMessage(), second);
                throw giveUp(second);
            }
        }
    }

    private McpUnavailableException giveUp(SessionLost cause) {
        String reason = "still failing after a new session: " + cause.getMessage();
        if (unavailable.compareAndSet(null, reason)) {
            log.warn("MCP server {} is unavailable: {}", url, reason);
            try {
                onUnavailable.accept(reason);
            } catch (RuntimeException e) {
                log.warn("MCP server {}: unavailable callback failed: {}", url, e.toString());
            }
        }
        return new McpUnavailableException("MCP server " + url + " is unavailable: " + unavailable.get(), cause);
    }

    @Override
    public List<JsonNode> listTools() throws IOException, InterruptedException {
        List<JsonNode> tools = new ArrayList<>();
        withSession(NO_PRINCIPAL, Map.of(), () -> {
            tools.clear();
            String cursor = null;
            do {
                ObjectNode params = mapper.createObjectNode();
                if (cursor != null) params.put("cursor", cursor);
                JsonNode result = post(request("tools/list", params), true, NO_PRINCIPAL, Map.of());
                result.path("tools").forEach(tools::add);
                cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
            } while (cursor != null);
            return null;
        });
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
        Map<String, String> extra = extraHeaders == null ? Map.of() : extraHeaders;
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments);
        return withSession(key, extra, () -> post(request("tools/call", params), true, key, extra));
    }

    private ObjectNode request(String method, JsonNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", method);
        req.set("params", params);
        return req;
    }

    private JsonNode post(ObjectNode body, boolean expectResult, String key, Map<String, String> extra)
            throws IOException, InterruptedException {
        String session = sessionIds.get(key);
        HttpResponse<String> resp = send(body, session, extra);
        if (resp.statusCode() == 401 && ownCredentials(extra)) {
            throw new McpUnauthorizedException("MCP " + url + " answered HTTP 401 to the caller's credentials");
        }
        if (resp.statusCode() == 401 && auth != null) {
            auth.onUnauthorized();
            resp = send(body, session, extra);
        }
        if (resp.statusCode() == 404) {
            throw new SessionLost("MCP " + url + " answered HTTP 404" + (session == null ? "" : " for session " + session),
                    session, null);
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

    private HttpResponse<String> send(ObjectNode body, String session, Map<String, String> extra)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (session != null) b.header("Mcp-Session-Id", session);
        headers.forEach(b::header);
        extra.forEach(b::header);
        if (auth != null && !ownCredentials(extra)) { // a per-user token replaces the shared login
            try {
                auth.headers().forEach(b::header);
            } catch (IllegalStateException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        HttpRequest request = b.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            // A request timeout may mean the server is still working on it: repeating it could run a tool twice.
            if (!(e instanceof HttpConnectTimeoutException)) throw e;
            throw new SessionLost("MCP " + url + " connection failed: " + e, session, e);
        } catch (IOException e) {
            throw new SessionLost("MCP " + url + " connection failed: " + e, session, e);
        }
    }

    /** The call carries the caller's own {@code Authorization} header. */
    private static boolean ownCredentials(Map<String, String> extra) {
        for (String k : extra.keySet()) if (k.equalsIgnoreCase("authorization")) return true;
        return false;
    }

    /**
     * Reads a plain JSON body, or the response to request {@code id} out of an SSE stream. Other messages of the
     * stream (notifications, server requests, responses to other ids) and non-JSON data are skipped.
     */
    private JsonNode parse(HttpResponse<String> resp, long id) throws IOException {
        String type = resp.headers().firstValue("Content-Type").orElse("");
        String text = resp.body();
        if (!type.contains("text/event-stream")) return mapper.readTree(text);
        List<String> data = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) {
                JsonNode found = response(data, id);
                if (found != null) return found;
                data.clear();
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        JsonNode found = response(data, id);
        if (found != null) return found;
        throw new IOException("MCP " + url + " sent no response for request " + id);
    }

    /** The response to {@code id} in the data of one SSE event (a message or a batch), else null. */
    private JsonNode response(List<String> data, long id) {
        if (data.isEmpty()) return null;
        JsonNode node;
        try {
            node = mapper.readTree(String.join("\n", data));
        } catch (IOException e) {
            log.debug("MCP {}: skipping non-JSON event data", url);
            return null;
        }
        if (node == null) return null;
        if (node.isArray()) {
            for (JsonNode n : node) if (isResponse(n, id)) return n;
            return null;
        }
        return isResponse(node, id) ? node : null;
    }

    private static boolean isResponse(JsonNode node, long id) {
        if (!node.isObject() || node.hasNonNull("method")) return false;
        JsonNode nid = node.get("id");
        if (nid == null) return false;
        if (nid.isIntegralNumber()) return nid.asLong() == id;
        return nid.isTextual() && nid.asText().equals(Long.toString(id));
    }
}

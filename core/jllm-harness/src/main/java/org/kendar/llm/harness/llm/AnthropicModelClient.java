package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Anthropic Messages API over plain HTTP, keeping messages as raw JSON. {@link #stream} uses server-sent events and
 * rebuilds the same message a non-streamed call returns.
 */

public class AnthropicModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicModelClient.class);
    private static final String API_VERSION = "2023-06-01";

    private final AnthropicSettings props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    /** Tool whose JSON results are re-sent to the API as citable search_result blocks. */
    static final String RAG_TOOL = "search_local_knowledge";

    private final long backoffBaseMs;
    private volatile org.kendar.llm.harness.auth.AuthProvider auth;
    private volatile boolean streaming = true;

    /** false: {@link #stream} makes a plain call (for proxies whose streaming misbehaves). Default true. */
    public AnthropicModelClient streaming(boolean on) {
        this.streaming = on;
        return this;
    }

    /** Headers come from the provider (OAuth, rotating keys) instead of the static {@code apiKey}. */
    public AnthropicModelClient authProvider(org.kendar.llm.harness.auth.AuthProvider provider) {
        this.auth = provider;
        return this;
    }

    public AnthropicModelClient(AnthropicSettings props, ObjectMapper mapper) {
        this(props, mapper, 1000L);
    }

    /** @param backoffBaseMs first retry delay when no retry-after header is present (doubles each attempt) */
    public AnthropicModelClient(AnthropicSettings props, ObjectMapper mapper, long backoffBaseMs) {
        this.props = props;
        this.mapper = mapper;
        this.backoffBaseMs = backoffBaseMs;
    }

    @Override
    public ModelResponse complete(ModelRequest req) {
        return complete(req, CancelToken.NONE);
    }

    @Override
    public ModelResponse complete(ModelRequest req, CancelToken cancel) {
        checkKey();
        return toResponse(send(buildBody(req), cancel));
    }

    @Override
    public ModelResponse stream(ModelRequest req, CancelToken cancel, StreamListener listener) {
        if (!streaming || listener == null) return complete(req, cancel);
        checkKey();
        ObjectNode body = buildBody(req);
        body.put("stream", true);
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new ModelException("Cannot serialize request", e);
        }
        JsonNode json = StreamingCall.run(http, () -> request(payload), auth, cancel, listener, props.maxRetries(),
                backoffBaseMs, s -> s == 429 || s == 529 || s >= 500, s -> "Anthropic API error " + s,
                "Anthropic API call failed: ", () -> this::assemble);
        return toResponse(json);
    }

    private void checkKey() {
        if (auth == null && (props.apiKey() == null || props.apiKey().isBlank())) {
            throw new ModelException("Anthropic API key (ANTHROPIC_API_KEY) is not configured");
        }
    }

    /**
     * Rebuilds the message of a streamed response: {@code message_start} gives the envelope, each content block is
     * started, filled by its deltas and closed, {@code message_delta} carries the stop reason and the output usage.
     */
    JsonNode assemble(StreamingCall.Events events, StreamListener listener) throws IOException {
        ObjectNode message = null;
        java.util.Map<Integer, StringBuilder> json = new java.util.HashMap<>();
        for (StreamingCall.Event ev = events.next(); ev != null; ev = events.next()) {
            JsonNode data;
            try {
                data = mapper.readTree(ev.data());
            } catch (IOException e) {
                throw new ModelException("Anthropic API returned a malformed stream event: " + truncate(ev.data()));
            }
            String type = data.path("type").asText(ev.event() == null ? "" : ev.event());
            switch (type) {
                case "message_start" -> {
                    message = data.path("message").isObject() ? (ObjectNode) data.get("message").deepCopy()
                            : mapper.createObjectNode();
                    if (!message.path("content").isArray()) message.putArray("content");
                }
                case "content_block_start" -> {
                    ArrayNode content = content(message);
                    int i = data.path("index").asInt(content.size());
                    ObjectNode block = data.path("content_block").isObject()
                            ? (ObjectNode) data.get("content_block").deepCopy() : mapper.createObjectNode();
                    while (content.size() <= i) content.addNull();
                    content.set(i, block);
                    if (block.has("input")) {
                        json.put(i, new StringBuilder());
                        listener.onToolCall(i, block.path("id").asText(null), block.path("name").asText(null), "");
                    }
                }
                case "content_block_delta" -> {
                    int i = data.path("index").asInt();
                    ObjectNode block = block(message, i);
                    JsonNode d = data.path("delta");
                    switch (d.path("type").asText()) {
                        case "text_delta" -> {
                            String t = d.path("text").asText("");
                            block.put("text", block.path("text").asText("") + t);
                            listener.onText(i, t);
                        }
                        case "thinking_delta" -> {
                            String t = d.path("thinking").asText("");
                            block.put("thinking", block.path("thinking").asText("") + t);
                            listener.onThinking(i, t);
                        }
                        case "signature_delta" ->
                                block.put("signature", block.path("signature").asText("") + d.path("signature").asText(""));
                        case "input_json_delta" -> {
                            String part = d.path("partial_json").asText("");
                            json.computeIfAbsent(i, k -> new StringBuilder()).append(part);
                            listener.onToolCall(i, null, null, part);
                        }
                        case "citations_delta" -> {
                            ArrayNode c = block.path("citations").isArray() ? (ArrayNode) block.get("citations")
                                    : block.putArray("citations");
                            c.add(d.path("citation"));
                        }
                        default -> { }
                    }
                }
                case "content_block_stop" -> {
                    int i = data.path("index").asInt();
                    StringBuilder raw = json.remove(i);
                    if (raw != null) block(message, i).set("input", input(raw.toString()));
                }
                case "message_delta" -> {
                    if (message == null) throw new StreamingCall.StreamError("message_delta before message_start", true);
                    JsonNode d = data.path("delta");
                    for (var it = d.fields(); it.hasNext(); ) {
                        var f = it.next();
                        message.set(f.getKey(), f.getValue());
                    }
                    if (data.path("usage").isObject()) {
                        ObjectNode u = message.path("usage").isObject() ? (ObjectNode) message.get("usage")
                                : message.putObject("usage");
                        for (var it = data.get("usage").fields(); it.hasNext(); ) {
                            var f = it.next();
                            if (!f.getValue().isNull()) u.set(f.getKey(), f.getValue());
                        }
                    }
                }
                case "message_stop" -> {
                    if (message == null) throw new StreamingCall.StreamError("stream ended without a message", true);
                    // blocks still open (should not happen) keep what arrived
                    for (var e : json.entrySet()) block(message, e.getKey()).set("input", input(e.getValue().toString()));
                    return message;
                }
                case "error" -> {
                    String et = data.path("error").path("type").asText("");
                    String em = data.path("error").path("message").asText("");
                    boolean retry = et.equals("overloaded_error") || et.equals("api_error");
                    throw new StreamingCall.StreamError("Anthropic API error: " + (et.isEmpty() ? "" : et + ": ")
                            + truncate(em.isEmpty() ? data.toString() : em), retry);
                }
                default -> { } // ping and unknown events
            }
        }
        throw new StreamingCall.StreamError("the stream ended before message_stop", true);
    }

    private ArrayNode content(ObjectNode message) throws IOException {
        if (message == null) throw new StreamingCall.StreamError("stream event before message_start", true);
        return (ArrayNode) message.get("content");
    }

    private ObjectNode block(ObjectNode message, int i) throws IOException {
        ArrayNode content = content(message);
        while (content.size() <= i) content.addNull();
        if (!(content.get(i) instanceof ObjectNode o)) {
            ObjectNode b = mapper.createObjectNode();
            content.set(i, b);
            return b;
        }
        return o;
    }

    /** The input of a streamed tool call: {} when empty; arguments cut off mid-JSON are kept raw. */
    private JsonNode input(String raw) {
        if (raw.isBlank()) return mapper.createObjectNode();
        try {
            JsonNode n = mapper.readTree(raw);
            if (n != null && n.isObject()) return n;
        } catch (IOException ignored) {
            // cut off: below
        }
        return mapper.createObjectNode().put(PartialToolCall.RAW, raw);
    }

    /** The response of a message JSON, streamed or not. */
    private ModelResponse toResponse(JsonNode json) {
        // a proxy may answer 200 with an error body ({"type":"error","error":{...}})
        if ("error".equals(json.path("type").asText()) || json.path("error").isObject()) {
            JsonNode err = json.path("error");
            String type = err.path("type").asText("");
            String message = err.path("message").asText("");
            throw new ModelException("Anthropic API error: " + (type.isEmpty() ? "" : type + ": ")
                    + (message.isEmpty() ? truncate(json.toString()) : truncate(message)));
        }

        ArrayNode content = json.path("content").isArray() ? (ArrayNode) json.get("content") : mapper.createArrayNode();
        JsonNode u = json.path("usage");
        Usage usage = new Usage(u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                u.path("cache_read_input_tokens").asLong(0), u.path("cache_creation_input_tokens").asLong(0));
        return new ModelResponse(content, json.path("stop_reason").asText("end_turn"), usage);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return simpleComplete(system, user, maxTokens, null);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, java.util.function.Consumer<Usage> usageSink) {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.put("content", user);
        ModelResponse r = complete(new ModelRequest(system, "", messages, java.util.List.of(), maxTokens));
        if (usageSink != null && r.usage() != null) usageSink.accept(r.usage());
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : r.content()) {
            if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText());
        }
        return sb.toString();
    }

    ObjectNode buildBody(ModelRequest req) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());

        int thinking = req.thinkingLevel() != null ? req.thinkingLevel().anthropicBudget() : props.thinkingBudget();
        int maxTokens = req.maxTokens() > 0 ? req.maxTokens() : props.maxTokens();
        if (thinking >= 1024) {
            maxTokens = Math.max(maxTokens, thinking + 1024);
            ObjectNode t = body.putObject("thinking");
            t.put("type", "enabled");
            t.put("budget_tokens", thinking);
        }
        body.put("max_tokens", maxTokens);

        ArrayNode system = body.putArray("system");
        ObjectNode pinned = system.addObject();
        pinned.put("type", "text");
        pinned.put("text", req.pinnedSystem());
        ObjectNode last = pinned;
        if (req.extraSystem() != null && !req.extraSystem().isBlank()) {
            last = system.addObject();
            last.put("type", "text");
            last.put("text", req.extraSystem());
        }
        cacheControl(last);

        if (req.tools() != null && !req.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            ObjectNode lastTool = null;
            for (HarnessTool t : req.tools()) {
                lastTool = tools.addObject();
                lastTool.put("name", t.name());
                lastTool.put("description", t.description());
                lastTool.set("input_schema", t.inputSchema());
            }
            cacheControl(lastTool);
            applyToolChoice(body, req, thinking >= 1024);
        }
        ArrayNode msgs = withoutFinishedThinking(req.messages());
        body.set("messages", props.citations() ? withSearchResults(msgs) : msgs);
        return body;
    }

    /**
     * Copy of the messages without the thinking blocks of finished turns. The API ignores them there anyway, and a
     * signature is bound to the content before it: once that was rewritten (compaction, masking, a resumed chat saved
     * before a rewrite) the block is rejected with a 400. The turn in progress keeps its blocks.
     */
    ArrayNode withoutFinishedThinking(ArrayNode messages) {
        int cut = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (plainUserText(messages.get(i))) {
                cut = i;
                break;
            }
        }
        ArrayNode out = null;
        for (int i = 0; i < cut; i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText()) || !m.path("content").isArray()) continue;
            ArrayNode kept = mapper.createArrayNode();
            for (JsonNode b : m.get("content")) {
                String t = b.path("type").asText();
                if (!"thinking".equals(t) && !"redacted_thinking".equals(t)) kept.add(b);
            }
            if (kept.size() == m.get("content").size() || kept.isEmpty()) continue;
            if (out == null) out = messages.deepCopy();
            ((ObjectNode) out.get(i)).set("content", kept);
        }
        return out != null ? out : messages;
    }

    private static boolean plainUserText(JsonNode msg) {
        if (!"user".equals(msg.path("role").asText())) return false;
        JsonNode c = msg.path("content");
        if (c.isTextual()) return true;
        if (!c.isArray() || c.isEmpty()) return false;
        for (JsonNode b : c) if (!"text".equals(b.path("type").asText())) return false;
        return true;
    }

    private void applyToolChoice(ObjectNode body, ModelRequest req, boolean thinkingOn) {
        String tc = req.toolChoice();
        boolean auto = tc == null || tc.isBlank() || "auto".equalsIgnoreCase(tc);
        boolean none = "none".equalsIgnoreCase(tc);
        if (auto && !req.disableParallelToolUse()) return;
        ObjectNode choice = mapper.createObjectNode();
        if (auto) {
            choice.put("type", "auto");
        } else if (none) {
            choice.put("type", "none");
        } else if (thinkingOn) {
            // the API rejects forced tool use together with extended thinking
            log.warn("toolChoice '{}' ignored: not allowed with extended thinking", tc);
            choice.put("type", "auto");
        } else if ("any".equalsIgnoreCase(tc)) {
            choice.put("type", "any");
        } else {
            choice.put("type", "tool");
            choice.put("name", tc);
        }
        if (req.disableParallelToolUse() && !none) choice.put("disable_parallel_tool_use", true);
        body.set("tool_choice", choice);
    }

    /**
     * Returns a copy of the messages in which RAG tool results (a JSON array of chunks carrying sourceId/content)
     * become {@code search_result} blocks with citations enabled. Anything unparseable is left as plain text.
     */
    ArrayNode withSearchResults(ArrayNode messages) {
        java.util.Set<String> ragIds = new java.util.HashSet<>();
        for (JsonNode m : messages) {
            if (!"assistant".equals(m.path("role").asText()) || !m.path("content").isArray()) continue;
            for (JsonNode b : m.get("content")) {
                if ("tool_use".equals(b.path("type").asText()) && RAG_TOOL.equals(b.path("name").asText())) {
                    ragIds.add(b.path("id").asText());
                }
            }
        }
        if (ragIds.isEmpty()) return messages;
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode m : messages) {
            if (!"user".equals(m.path("role").asText()) || !m.path("content").isArray()) {
                out.add(m);
                continue;
            }
            ObjectNode copy = m.deepCopy();
            ArrayNode blocks = (ArrayNode) copy.get("content");
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode b = blocks.get(i);
                if ("tool_result".equals(b.path("type").asText()) && ragIds.contains(b.path("tool_use_id").asText())
                        && !b.path("is_error").asBoolean(false) && b.path("content").isTextual()) {
                    ArrayNode converted = toSearchResults(b.path("content").asText());
                    if (converted != null) ((ObjectNode) b).set("content", converted);
                }
            }
            out.add(copy);
        }
        return out;
    }

    private ArrayNode toSearchResults(String text) {
        try {
            JsonNode parsed = mapper.readTree(text);
            if (parsed == null || !parsed.isArray() || parsed.isEmpty()) return null;
            ArrayNode blocks = mapper.createArrayNode();
            for (JsonNode c : parsed) {
                if (!c.hasNonNull("sourceId") || !c.hasNonNull("content")) return null;
                ObjectNode sr = blocks.addObject();
                sr.put("type", "search_result");
                sr.put("source", c.get("sourceId").asText());
                sr.put("title", c.path("filePath").asText(c.get("sourceId").asText()));
                sr.putArray("content").addObject().put("type", "text").put("text", c.get("content").asText());
                sr.putObject("citations").put("enabled", true);
            }
            return blocks;
        } catch (IOException e) {
            return null;
        }
    }

    private void cacheControl(ObjectNode block) {
        ObjectNode cc = block.putObject("cache_control");
        cc.put("type", "ephemeral");
        if ("1h".equals(props.cacheTtl())) cc.put("ttl", "1h");
    }

    private HttpRequest request(String payload) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + "/v1/messages"))
                .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("content-type", "application/json")
                .header("anthropic-version", API_VERSION);
        if (auth != null) {
            try {
                auth.headers().forEach(rb::header);
            } catch (RuntimeException e) {
                throw new ModelException("Authentication failed: " + e.getMessage(), e);
            }
        } else {
            rb.header("x-api-key", props.apiKey());
        }
        return rb.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    }

    private JsonNode send(ObjectNode body, CancelToken cancel) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new ModelException("Cannot serialize request", e);
        }
        java.util.function.Supplier<HttpRequest> build = () -> request(payload);
        HttpRequest request = build.get();
        boolean authRetried = false;

        int attempt = 0;
        while (true) {
            try {
                HttpResponse<String> resp = cancel.send(http, request);
                int status = resp.statusCode();
                if (status == 200) {
                    return readJson(resp.body());
                }
                if (status == 401 && auth != null && !authRetried) {
                    authRetried = true;
                    auth.onUnauthorized();
                    request = build.get();
                    continue;
                }
                boolean retryable = status == 429 || status == 529 || status >= 500;
                if (!retryable || attempt >= props.maxRetries()) {
                    throw new ModelException("Anthropic API error " + status + ": " + truncate(resp.body()));
                }
                long delayMs = resp.headers().firstValue("retry-after")
                        .map(AnthropicModelClient::parseSeconds)
                        .orElse(Math.min(30_000L, backoffBaseMs << attempt));
                log.warn("Anthropic API {} — retry {} in {} ms", status, attempt + 1, delayMs);
                attempt++;
                cancel.retrying(status, attempt, props.maxRetries(), delayMs);
                cancel.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted while calling the model", e);
            } catch (IOException e) {
                if (attempt >= props.maxRetries()) {
                    throw new ModelException("Anthropic API call failed: " + e.getMessage(), e);
                }
                attempt++;
                long netDelay = Math.min(30_000L, backoffBaseMs << attempt);
                cancel.retrying(0, attempt, props.maxRetries(), netDelay);
                try {
                    cancel.sleep(netDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ModelException("Interrupted while calling the model", ie);
                }
            }
        }
    }

    /** A 200 body must be a JSON object; anything else (HTML from a proxy or a wrong baseUrl) fails at once. */
    private JsonNode readJson(String body) {
        try {
            JsonNode json = body == null ? null : mapper.readTree(body);
            if (json != null && json.isObject()) return json;
        } catch (IOException e) {
            // not JSON: reported below
        }
        throw new ModelException("Anthropic API returned a non-JSON response (check baseUrl): " + truncate(body));
    }

    private static long parseSeconds(String v) {
        try {
            return Math.min(120_000L, (long) (Double.parseDouble(v.trim()) * 1000));
        } catch (NumberFormatException e) {
            return 2000L;
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 1000 ? s.substring(0, 1000) + "…" : s;
    }
}

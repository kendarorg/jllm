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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * OpenAI Responses API client ({@code POST /responses}) for GPT-6.2 and later (provider {@code openaiGpt62}): those
 * models accept Chat Completions but call tools only through Responses.
 *
 * <p>Prompt caching rules (GPT-6.2 appends top-level {@code instructions} after {@code input} and never caches them):
 * <ul>
 *   <li>the system prompt is sent as leading {@code developer} messages in {@code input} (pinned first, then the policy
 *       prompt), never as {@code instructions};</li>
 *   <li>the tool list is sent in the request order with {@code strict: false}; callers keep it unchanged within a
 *       chat to keep the prefix cached;</li>
 *   <li>the request is stateless ({@code store: false}): reasoning items come back with their encrypted content and
 *       are kept in the log as {@code thinking} blocks carrying the raw item in {@value #REASONING_FIELD}; they are
 *       passed back unchanged and in order.</li>
 * </ul>
 * GPT-6.2 rejects {@code temperature}, so it is never sent; the {@code minimal} thinking level is sent as {@code low}.
 */
public class OpenAiResponsesModelClient implements ModelClient {

    /** Provider type; matched case-insensitively ({@code openaiGpt62}). */
    public static final String PROVIDER = "openaigpt62";
    public static final String DEFAULT_MODEL = "gpt-6.2-sol";
    /** Field of a {@code thinking} block holding the raw Responses reasoning item. */
    public static final String REASONING_FIELD = "openai_reasoning";

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesModelClient.class);

    private final LlmSettings cfg;
    private final ObjectMapper mapper;
    private final long backoffBaseMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private volatile boolean streaming = true;
    private volatile org.kendar.llm.harness.auth.AuthProvider auth;

    public OpenAiResponsesModelClient(LlmSettings cfg, ObjectMapper mapper) {
        this(cfg, mapper, 1000L);
    }

    /** @param backoffBaseMs first retry delay when no retry-after header is present (doubles each attempt) */
    public OpenAiResponsesModelClient(LlmSettings cfg, ObjectMapper mapper, long backoffBaseMs) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.backoffBaseMs = backoffBaseMs;
    }

    /** false: {@link #stream} makes a plain call. Default true. */
    public OpenAiResponsesModelClient streaming(boolean on) {
        this.streaming = on;
        return this;
    }

    /** Headers come from the provider (OAuth, rotating keys) instead of the static {@code apiKey}. */
    public OpenAiResponsesModelClient authProvider(org.kendar.llm.harness.auth.AuthProvider provider) {
        this.auth = provider;
        return this;
    }

    @Override
    public ModelResponse complete(ModelRequest req) {
        return complete(req, CancelToken.NONE);
    }

    @Override
    public ModelResponse complete(ModelRequest req, CancelToken cancel) {
        checkKey();
        ObjectNode body = buildBody(req);
        for (int attempt = 0; ; attempt++) {
            try {
                return parseResponse(send(body, cancel));
            } catch (ModelException e) {
                if (attempt < 3 && adaptToRejection(body, e)) continue;
                throw e;
            }
        }
    }

    @Override
    public ModelResponse stream(ModelRequest req, CancelToken cancel, StreamListener listener) {
        if (!streaming || listener == null) return complete(req, cancel);
        checkKey();
        ObjectNode body = buildBody(req);
        body.put("stream", true);
        for (int attempt = 0; ; attempt++) {
            String payload = serialize(body);
            try {
                return parseResponse(StreamingCall.run(http, () -> request(payload), auth, cancel, listener,
                        cfg.maxRetries(), backoffBaseMs, s -> s == 429 || s >= 500,
                        s -> cfg.name() + " API error " + s, cfg.name() + " API call failed: ",
                        () -> this::assemble));
            } catch (ModelException e) {
                if (e instanceof StreamingCall.AfterFirstDelta) throw e;
                if (attempt < 3 && adaptToRejection(body, e)) continue;
                throw e;
            }
        }
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return simpleComplete(system, user, maxTokens, null);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, java.util.function.Consumer<Usage> usageSink) {
        return SimpleCompletion.run(this, system, user, maxTokens, null, usageSink);
    }

    private void checkKey() {
        if (auth == null && cfg.requireApiKey() && (cfg.apiKey() == null || cfg.apiKey().isBlank())) {
            throw new ModelException("API key for LLM provider '" + cfg.name() + "' is not configured");
        }
    }

    // ---------------------------------------------------------------- request translation

    ObjectNode buildBody(ModelRequest req) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.model());
        body.put("store", false);
        body.putArray("include").add("reasoning.encrypted_content");
        body.put("max_output_tokens", req.maxTokens() > 0 ? req.maxTokens() : cfg.maxTokens());
        ObjectNode reasoning = body.putObject("reasoning");
        if (req.thinkingLevel() != null) {
            reasoning.put("effort", req.thinkingLevel() == ThinkingLevel.MINIMAL ? "low"
                    : req.thinkingLevel().reasoningEffort(cfg.name()));
        }
        reasoning.put("summary", "auto");

        ArrayNode input = body.putArray("input");
        // two items: a change of the policy prompt keeps the pinned one cached
        developer(input, req.pinnedSystem());
        developer(input, req.extraSystem());
        if (req.messages() != null) {
            for (JsonNode msg : req.messages()) translateMessage(msg, input);
        }

        if (req.tools() != null && !req.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (HarnessTool t : req.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                tool.put("name", t.name());
                tool.put("description", t.description());
                tool.set("parameters", t.inputSchema());
                // strict mode needs every property required and no additional ones; the harness schemas are not
                tool.put("strict", false);
            }
            String tc = req.toolChoice();
            if (tc != null && !tc.isBlank() && !"auto".equalsIgnoreCase(tc)) {
                if ("any".equalsIgnoreCase(tc)) body.put("tool_choice", "required");
                else if ("none".equalsIgnoreCase(tc)) body.put("tool_choice", "none");
                else body.putObject("tool_choice").put("type", "function").put("name", tc);
            }
            if (req.disableParallelToolUse()) body.put("parallel_tool_calls", false);
        }
        return body;
    }

    private static void developer(ArrayNode input, String text) {
        if (text == null || text.isBlank()) return;
        input.addObject().put("role", "developer").put("content", text);
    }

    private void translateMessage(JsonNode msg, ArrayNode input) {
        String role = msg.path("role").asText();
        JsonNode content = msg.path("content");
        if (content.isTextual()) {
            if (!content.asText().isEmpty()) input.addObject().put("role", role).put("content", content.asText());
            return;
        }
        if (!content.isArray()) return;

        if ("assistant".equals(role)) {
            // output items in their original order: reasoning, text, function calls
            StringBuilder text = new StringBuilder();
            for (JsonNode b : content) {
                String type = b.path("type").asText();
                if ("text".equals(type)) {
                    text.append(b.path("text").asText());
                    continue;
                }
                if ("thinking".equals(type) && b.path(REASONING_FIELD).isObject()) {
                    flushAssistant(input, text);
                    input.add(b.get(REASONING_FIELD).deepCopy());
                } else if ("tool_use".equals(type)) {
                    flushAssistant(input, text);
                    ObjectNode c = input.addObject();
                    c.put("type", "function_call");
                    c.put("call_id", b.path("id").asText());
                    c.put("name", b.path("name").asText());
                    JsonNode in = b.has("input") ? b.get("input") : mapper.createObjectNode();
                    c.put("arguments", in.toString());
                }
                // thinking of another provider (Anthropic signature) cannot be replayed here
            }
            flushAssistant(input, text);
        } else {
            StringBuilder text = new StringBuilder();
            for (JsonNode b : content) {
                String type = b.path("type").asText();
                if ("tool_result".equals(type)) {
                    ObjectNode o = input.addObject();
                    o.put("type", "function_call_output");
                    o.put("call_id", b.path("tool_use_id").asText());
                    String t = flatten(b.path("content"));
                    if (b.path("is_error").asBoolean(false) && !t.startsWith("Error")) t = "Error: " + t;
                    o.put("output", t);
                } else if ("text".equals(type)) {
                    if (!text.isEmpty()) text.append("\n");
                    text.append(b.path("text").asText());
                }
            }
            if (!text.isEmpty()) input.addObject().put("role", role).put("content", text.toString());
        }
    }

    private static void flushAssistant(ArrayNode input, StringBuilder text) {
        if (text.isEmpty()) return;
        input.addObject().put("role", "assistant").put("content", text.toString());
        text.setLength(0);
    }

    private static String flatten(JsonNode c) {
        if (c == null || c.isMissingNode() || c.isNull()) return "";
        if (c.isTextual()) return c.asText();
        if (c.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : c) {
                if (p.isTextual()) sb.append(p.asText());
                else if (p.has("text")) sb.append(p.path("text").asText());
            }
            return sb.toString();
        }
        return c.toString();
    }

    /**
     * A model that rejects an optional parameter gets a retry without it: the reasoning summary, then the effort.
     * Returns true when the body was changed.
     */
    private boolean adaptToRejection(ObjectNode body, ModelException e) {
        if (e.getMessage() == null || !e.getMessage().contains("API error 400")) return false;
        String msg = e.getMessage().toLowerCase();
        ObjectNode reasoning = body.path("reasoning").isObject() ? (ObjectNode) body.get("reasoning") : null;
        if (reasoning != null && reasoning.has("summary") && msg.contains("summary")) {
            log.warn("{} rejected the reasoning summary; retrying without it", cfg.name());
            reasoning.remove("summary");
            return true;
        }
        if (reasoning != null && reasoning.has("effort") && msg.contains("effort")) {
            log.warn("{} rejected reasoning effort '{}'; retrying without it", cfg.name(), reasoning.path("effort").asText());
            reasoning.remove("effort");
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- response translation

    ModelResponse parseResponse(JsonNode json) {
        JsonNode error = json.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new ModelException(cfg.name() + " returned an error: " + truncate(errorText(error)));
        }
        JsonNode output = json.path("output");
        if (!output.isArray()) {
            throw new ModelException(cfg.name() + " returned no output: " + truncate(json.toString()));
        }
        ArrayNode content = mapper.createArrayNode();
        boolean hasCalls = false;
        boolean refused = false;
        for (JsonNode item : output) {
            switch (item.path("type").asText()) {
                case "reasoning" -> {
                    ObjectNode t = content.addObject();
                    t.put("type", "thinking");
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode s : item.path("summary")) {
                        if (!sb.isEmpty()) sb.append("\n\n");
                        sb.append(s.path("text").asText(""));
                    }
                    t.put("thinking", sb.toString());
                    t.set(REASONING_FIELD, item);
                }
                case "message" -> {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode p : item.path("content")) {
                        String pt = p.path("type").asText();
                        if ("output_text".equals(pt)) sb.append(p.path("text").asText(""));
                        else if ("refusal".equals(pt)) {
                            sb.append(p.path("refusal").asText(""));
                            refused = true;
                        }
                    }
                    if (!sb.isEmpty()) content.addObject().put("type", "text").put("text", sb.toString());
                }
                case "function_call" -> {
                    String name = item.path("name").asText("");
                    if (name.isEmpty()) continue;
                    hasCalls = true;
                    ObjectNode tu = content.addObject();
                    tu.put("type", "tool_use");
                    String id = item.path("call_id").asText("");
                    tu.put("id", id.isBlank() ? "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24) : id);
                    tu.put("name", name);
                    tu.set("input", parseArguments(item.path("arguments")));
                }
                default -> {
                    // other item types (web search, ...) are not requested by the harness
                }
            }
        }
        String reason = json.path("incomplete_details").path("reason").asText("");
        String stop;
        if ("incomplete".equals(json.path("status").asText()) && "max_output_tokens".equals(reason)) stop = "max_tokens";
        else if ("content_filter".equals(reason) || refused) stop = "refusal";
        else if (hasCalls) stop = "tool_use";
        else stop = "end_turn";
        return new ModelResponse(content, stop, parseUsage(json.path("usage")));
    }

    private JsonNode parseArguments(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) return mapper.createObjectNode();
        if (args.isObject()) return args;
        String s = args.asText("").trim();
        if (s.isEmpty()) return mapper.createObjectNode();
        try {
            JsonNode parsed = mapper.readTree(s);
            if (parsed != null && parsed.isObject()) return parsed;
        } catch (IOException ignored) {
            // cut off: kept raw below
        }
        ObjectNode raw = mapper.createObjectNode();
        raw.put(PartialToolCall.RAW, s);
        return raw;
    }

    private static Usage parseUsage(JsonNode u) {
        if (u == null || !u.isObject()) return Usage.ZERO;
        long input = u.path("input_tokens").asLong(0);
        long cached = Math.min(u.path("input_tokens_details").path("cached_tokens").asLong(0), input);
        return new Usage(input - cached, u.path("output_tokens").asLong(0), cached, 0);
    }

    /**
     * Streams the deltas to the listener and returns the {@code response} of the final event
     * ({@code response.completed} or {@code response.incomplete}), the same object a plain call returns.
     */
    JsonNode assemble(StreamingCall.Events events, StreamListener listener) throws IOException {
        Map<Integer, String[]> calls = new HashMap<>();
        for (StreamingCall.Event ev = events.next(); ev != null; ev = events.next()) {
            String data = ev.data().strip();
            if (data.equals("[DONE]")) break;
            JsonNode e;
            try {
                e = mapper.readTree(data);
            } catch (IOException ex) {
                throw new ModelException(cfg.name() + " returned a malformed stream event: " + truncate(data));
            }
            int index = e.path("output_index").asInt(0);
            switch (e.path("type").asText(ev.event() == null ? "" : ev.event())) {
                case "response.output_text.delta", "response.refusal.delta" ->
                        listener.onText(index, e.path("delta").asText(""));
                case "response.reasoning_summary_text.delta" -> listener.onThinking(index, e.path("delta").asText(""));
                case "response.output_item.added" -> {
                    JsonNode item = e.path("item");
                    if ("function_call".equals(item.path("type").asText())) {
                        String id = item.path("call_id").asText("");
                        String name = item.path("name").asText("");
                        calls.put(index, new String[]{id, name});
                        listener.onToolCall(index, id, name, "");
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    String[] c = calls.remove(index);
                    listener.onToolCall(index, c == null ? null : c[0], c == null ? null : c[1],
                            e.path("delta").asText(""));
                }
                case "response.completed", "response.incomplete" -> {
                    return e.path("response");
                }
                case "response.failed" -> {
                    JsonNode err = e.path("response").path("error");
                    throw new StreamingCall.StreamError(cfg.name() + " returned an error: "
                            + truncate(err.isMissingNode() || err.isNull() ? e.toString() : errorText(err)), false);
                }
                case "error" -> throw new StreamingCall.StreamError(cfg.name() + " returned an error: "
                        + truncate(errorText(e.has("error") ? e.get("error") : e)), false);
                default -> {
                    // created, in_progress, *.done, content_part.*: the final response carries them
                }
            }
        }
        throw new StreamingCall.StreamError("the stream ended before the response was complete", true);
    }

    private static String errorText(JsonNode error) {
        if (error.isTextual()) return error.asText();
        String message = error.path("message").asText("");
        if (message.isBlank()) return error.toString();
        JsonNode code = error.path("code");
        return code.isMissingNode() || code.isNull() ? message : message + " (code " + code.asText() + ")";
    }

    // ---------------------------------------------------------------- transport

    private String serialize(ObjectNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new ModelException("Cannot serialize request", e);
        }
    }

    private HttpRequest request(String payload) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cfg.baseUrl().replaceAll("/+$", "") + "/responses"))
                .timeout(Duration.ofSeconds(Math.max(1, cfg.timeoutSeconds())))
                .header("content-type", "application/json");
        if (auth != null) {
            try {
                auth.headers().forEach(rb::header);
            } catch (RuntimeException e) {
                throw new ModelException("Authentication failed: " + e.getMessage(), e);
            }
        } else if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            rb.header("authorization", "Bearer " + cfg.apiKey());
        }
        return rb.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    }

    private JsonNode send(ObjectNode body, CancelToken cancel) {
        String payload = serialize(body);
        java.util.function.Supplier<HttpRequest> build = () -> request(payload);
        HttpRequest request = build.get();
        boolean authRetried = false;
        int attempt = 0;
        RetryBudget budget = new RetryBudget();
        while (true) {
            try {
                HttpResponse<String> resp = cancel.send(http, request);
                int status = resp.statusCode();
                if (status == 200) return readJson(resp.body());
                if (status == 401 && auth != null && !authRetried) {
                    authRetried = true;
                    auth.onUnauthorized();
                    request = build.get();
                    continue;
                }
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= cfg.maxRetries()) {
                    throw new ModelException(cfg.name() + " API error " + status + ": " + truncate(resp.body())
                            + StreamingCall.rateLimitHint(resp));
                }
                attempt++;
                long delayMs = budget.reserve(attempt, backoffBaseMs,
                                resp.headers().firstValue("retry-after").map(OpenAiResponsesModelClient::parseSeconds))
                        .orElseThrow(() -> new ModelException(cfg.name() + " API error " + status + ": "
                                + truncate(resp.body()) + StreamingCall.rateLimitHint(resp)
                                + " (retry wait budget of " + RetryBudget.MAX_WAIT_MS + " ms exhausted)"));
                log.warn("{} API {} - retry {} in {} ms{}", cfg.name(), status, attempt, delayMs, StreamingCall.rateLimitHint(resp));
                cancel.retrying(status, attempt, cfg.maxRetries(), delayMs);
                cancel.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted while calling the model", e);
            } catch (IOException e) {
                if (attempt >= cfg.maxRetries()) {
                    throw new ModelException(cfg.name() + " API call failed: " + e.getMessage(), e);
                }
                attempt++;
                long netDelay = budget.reserve(attempt, backoffBaseMs, Optional.empty())
                        .orElseThrow(() -> new ModelException(cfg.name() + " API call failed: " + e.getMessage()
                                + " (retry wait budget of " + RetryBudget.MAX_WAIT_MS + " ms exhausted)", e));
                cancel.retrying(0, attempt, cfg.maxRetries(), netDelay);
                try {
                    cancel.sleep(netDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ModelException("Interrupted while calling the model", ie);
                }
            }
        }
    }

    private JsonNode readJson(String body) {
        try {
            JsonNode json = body == null ? null : mapper.readTree(body);
            if (json != null && json.isObject()) return json;
        } catch (IOException e) {
            // not JSON: reported below
        }
        throw new ModelException(cfg.name() + " returned a non-JSON response (check baseUrl): " + truncate(body));
    }

    private static long parseSeconds(String v) {
        try {
            return Math.min(120_000L, (long) (Double.parseDouble(v.trim()) * 1000));
        } catch (NumberFormatException e) {
            return 2000L;
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 1000 ? s.substring(0, 1000) + "..." : s;
    }
}

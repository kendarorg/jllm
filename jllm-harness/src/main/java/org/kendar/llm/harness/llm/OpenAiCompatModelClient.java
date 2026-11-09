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
import java.util.UUID;

/**
 * OpenAI Chat Completions client (OpenAI, Qwen/DashScope, vLLM, Ollama, llama.cpp server...).
 * Conversation state stays in Anthropic content-block JSON; it is translated on the way in and out.
 * Prompt caching and thinking are Anthropic-only and ignored here.
 */
public class OpenAiCompatModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatModelClient.class);

    private final LlmSettings cfg;
    private final ObjectMapper mapper;
    private final long backoffBaseMs;
    private volatile org.kendar.llm.harness.auth.AuthProvider auth;

    /** Headers come from the provider (OAuth, rotating keys) instead of the static {@code apiKey}. */
    public OpenAiCompatModelClient authProvider(org.kendar.llm.harness.auth.AuthProvider provider) {
        this.auth = provider;
        return this;
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public OpenAiCompatModelClient(LlmSettings cfg, ObjectMapper mapper) {
        this(cfg, mapper, 1000L);
    }

    /** @param backoffBaseMs first retry delay when no retry-after header is present (doubles each attempt) */
    public OpenAiCompatModelClient(LlmSettings cfg, ObjectMapper mapper, long backoffBaseMs) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.backoffBaseMs = backoffBaseMs;
    }

    @Override
    public ModelResponse complete(ModelRequest req) {
        return complete(req, CancelToken.NONE);
    }

    @Override
    public ModelResponse complete(ModelRequest req, CancelToken cancel) {
        if (auth == null && cfg.requireApiKey() && (cfg.apiKey() == null || cfg.apiKey().isBlank())) {
            throw new ModelException("API key for LLM provider '" + cfg.name() + "' is not configured");
        }
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

    /**
     * A model that rejects an optional parameter gets a retry without it; a rejected "minimal"/"none" reasoning
     * effort first steps down to "low". Returns true when the body was changed.
     */
    private boolean adaptToRejection(ObjectNode body, ModelException e) {
        if (e.getMessage() == null || !e.getMessage().contains("API error 400")) return false;
        String msg = e.getMessage().toLowerCase();
        if (body.has("temperature") && msg.contains("temperature")) {
            log.warn("{} rejected temperature; retrying without it", cfg.name());
            body.remove("temperature");
            return true;
        }
        if (body.has("reasoning_effort") && msg.contains("reasoning")) {
            String effort = body.path("reasoning_effort").asText();
            if ("minimal".equals(effort) || "none".equals(effort)) {
                log.warn("{} rejected reasoning_effort '{}'; stepping down to 'low'", cfg.name(), effort);
                body.put("reasoning_effort", "low");
            } else {
                log.warn("{} rejected reasoning_effort '{}'; retrying without it", cfg.name(), effort);
                body.remove("reasoning_effort");
            }
            return true;
        }
        return false;
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return simpleComplete(system, user, maxTokens, null);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, java.util.function.Consumer<Usage> usageSink) {
        return simpleComplete(system, user, maxTokens, null, usageSink);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 java.util.function.Consumer<Usage> usageSink) {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.put("content", user);
        ModelRequest request = new ModelRequest(system, "", messages, java.util.List.of(), maxTokens);
        ModelResponse r = complete(temperature == null ? request : request.withTemperature(temperature));
        if (usageSink != null && r.usage() != null) usageSink.accept(r.usage());
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : r.content()) {
            if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- request translation

    ObjectNode buildBody(ModelRequest req) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.model());
        int maxTokens = req.maxTokens() > 0 ? req.maxTokens() : cfg.maxTokens();
        body.put(cfg.useMaxCompletionTokens() ? "max_completion_tokens" : "max_tokens", maxTokens);
        if (req.temperature() != null && req.temperature() >= 0 && req.temperature() <= 2) {
            body.put("temperature", req.temperature());
        }
        if (req.thinkingLevel() != null) {
            body.put("reasoning_effort", req.thinkingLevel().reasoningEffort(cfg.name()));
        }

        ArrayNode out = body.putArray("messages");
        StringBuilder sys = new StringBuilder();
        if (req.pinnedSystem() != null && !req.pinnedSystem().isBlank()) sys.append(req.pinnedSystem());
        if (req.extraSystem() != null && !req.extraSystem().isBlank()) {
            if (sys.length() > 0) sys.append("\n\n");
            sys.append(req.extraSystem());
        }
        if (sys.length() > 0) {
            ObjectNode s = out.addObject();
            s.put("role", "system");
            s.put("content", sys.toString());
        }

        if (req.messages() != null) {
            for (JsonNode msg : req.messages()) translateMessage(msg, out);
        }

        if (req.tools() != null && !req.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (HarnessTool t : req.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.inputSchema());
            }
            String tc = req.toolChoice();
            if (tc != null && !tc.isBlank() && !"auto".equalsIgnoreCase(tc)) {
                if ("any".equalsIgnoreCase(tc)) body.put("tool_choice", "required");
                else if ("none".equalsIgnoreCase(tc)) body.put("tool_choice", "none");
                else {
                    ObjectNode c = body.putObject("tool_choice");
                    c.put("type", "function");
                    c.putObject("function").put("name", tc);
                }
            }
            if (req.disableParallelToolUse()) body.put("parallel_tool_calls", false);
        }
        return body;
    }

    private void translateMessage(JsonNode msg, ArrayNode out) {
        String role = msg.path("role").asText();
        JsonNode content = msg.path("content");
        if (content.isTextual()) {
            ObjectNode o = out.addObject();
            o.put("role", role);
            o.put("content", content.asText());
            return;
        }
        if (!content.isArray()) return;

        if ("assistant".equals(role)) {
            StringBuilder text = new StringBuilder();
            ArrayNode calls = mapper.createArrayNode();
            for (JsonNode b : content) {
                String type = b.path("type").asText();
                if ("text".equals(type)) {
                    text.append(b.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    ObjectNode c = calls.addObject();
                    c.put("id", b.path("id").asText());
                    c.put("type", "function");
                    ObjectNode fn = c.putObject("function");
                    fn.put("name", b.path("name").asText());
                    JsonNode input = b.has("input") ? b.get("input") : mapper.createObjectNode();
                    fn.put("arguments", input.toString());
                }
            }
            ObjectNode o = out.addObject();
            o.put("role", "assistant");
            if (text.length() == 0 && !calls.isEmpty()) o.putNull("content");
            else o.put("content", text.toString());
            if (!calls.isEmpty()) o.set("tool_calls", calls);
        } else {
            // user: tool results become role=tool messages (must directly follow the assistant tool_calls)
            StringBuilder text = new StringBuilder();
            for (JsonNode b : content) {
                String type = b.path("type").asText();
                if ("tool_result".equals(type)) {
                    ObjectNode o = out.addObject();
                    o.put("role", "tool");
                    o.put("tool_call_id", b.path("tool_use_id").asText());
                    String t = flatten(b.path("content"));
                    if (b.path("is_error").asBoolean(false) && !t.startsWith("Error")) t = "Error: " + t;
                    o.put("content", t);
                } else if ("text".equals(type)) {
                    if (text.length() > 0) text.append("\n");
                    text.append(b.path("text").asText());
                }
            }
            if (text.length() > 0) {
                ObjectNode o = out.addObject();
                o.put("role", role);
                o.put("content", text.toString());
            }
        }
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

    // ---------------------------------------------------------------- response translation

    ModelResponse parseResponse(JsonNode json) {
        ArrayNode content = mapper.createArrayNode();
        JsonNode choice = json.path("choices").path(0);
        JsonNode message = choice.path("message");

        String text = flatten(message.path("content"));
        if (!text.isEmpty()) {
            ObjectNode t = content.addObject();
            t.put("type", "text");
            t.put("text", text);
        }
        boolean hasCalls = false;
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode c : calls) {
                JsonNode fn = c.path("function");
                String name = fn.path("name").asText("");
                if (name.isEmpty()) continue;
                hasCalls = true;
                ObjectNode tu = content.addObject();
                tu.put("type", "tool_use");
                String id = c.path("id").asText("");
                tu.put("id", id.isBlank() ? "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24) : id);
                tu.put("name", name);
                tu.set("input", parseArguments(fn.path("arguments")));
            }
        }

        String finish = choice.path("finish_reason").asText("");
        String stop = switch (finish) {
            case "tool_calls", "function_call" -> "tool_use";
            case "length" -> "max_tokens";
            default -> "end_turn";
        };
        if (hasCalls && "end_turn".equals(stop)) stop = "tool_use"; // some local servers report "stop"
        if (!hasCalls && "tool_use".equals(stop)) stop = "end_turn";
        if ("max_tokens".equals(stop) && !hasCalls) content = recoverCutTextCall(content, text);

        return new ModelResponse(content, stop, parseUsage(json.path("usage")));
    }

    /**
     * A local server cut off while writing a tool call often returns the raw call ({@code <tool_call>...}) as text.
     * Turn it back into a tool_use block so the loop reports it as a cut-off call instead of plain text.
     */
    private ArrayNode recoverCutTextCall(ArrayNode content, String text) {
        int at = PartialToolCall.openTextCall(text);
        if (at < 0) return content;
        String fragment = text.substring(at);
        String name = PartialToolCall.textCallName(fragment);
        if (name == null) return content;
        ArrayNode out = mapper.createArrayNode();
        String before = text.substring(0, at);
        if (!before.isBlank()) out.addObject().put("type", "text").put("text", before);
        ObjectNode tu = out.addObject();
        tu.put("type", "tool_use");
        tu.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        tu.put("name", name);
        tu.putObject("input").put(PartialToolCall.RAW, fragment);
        return out;
    }

    private JsonNode parseArguments(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) return mapper.createObjectNode();
        if (args.isObject()) return args;
        if (args.isTextual()) {
            String s = args.asText().trim();
            if (s.isEmpty()) return mapper.createObjectNode();
            try {
                JsonNode parsed = mapper.readTree(s);
                if (parsed != null && parsed.isObject()) return parsed;
                if (parsed != null && parsed.isTextual()) { // double-encoded
                    JsonNode again = mapper.readTree(parsed.asText());
                    if (again != null && again.isObject()) return again;
                }
            } catch (IOException ignored) {
                // fall through
            }
            ObjectNode raw = mapper.createObjectNode();
            raw.put(PartialToolCall.RAW, s);
            return raw;
        }
        return mapper.createObjectNode();
    }

    private static Usage parseUsage(JsonNode u) {
        if (u == null || !u.isObject()) return Usage.ZERO;
        long prompt = u.path("prompt_tokens").asLong(0);
        long completion = u.path("completion_tokens").asLong(0);
        long cached = u.path("prompt_tokens_details").path("cached_tokens").asLong(u.path("cached_tokens").asLong(0));
        cached = Math.min(cached, prompt);
        return new Usage(prompt - cached, completion, cached, 0);
    }

    // ---------------------------------------------------------------- transport

    private JsonNode send(ObjectNode body, CancelToken cancel) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new ModelException("Cannot serialize request", e);
        }
        java.util.function.Supplier<HttpRequest> build = () -> {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
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
        };
        HttpRequest request = build.get();
        boolean authRetried = false;

        int attempt = 0;
        while (true) {
            try {
                HttpResponse<String> resp = cancel.send(http, request);
                int status = resp.statusCode();
                if (status == 200) {
                    return mapper.readTree(resp.body());
                }
                if (status == 401 && auth != null && !authRetried) {
                    authRetried = true;
                    auth.onUnauthorized();
                    request = build.get();
                    continue;
                }
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= cfg.maxRetries()) {
                    throw new ModelException(cfg.name() + " API error " + status + ": " + truncate(resp.body()));
                }
                long delayMs = resp.headers().firstValue("retry-after")
                        .map(OpenAiCompatModelClient::parseSeconds)
                        .orElse(Math.min(30_000L, backoffBaseMs << attempt));
                log.warn("{} API {} - retry {} in {} ms", cfg.name(), status, attempt + 1, delayMs);
                attempt++;
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
                long netDelay = Math.min(30_000L, backoffBaseMs << attempt);
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

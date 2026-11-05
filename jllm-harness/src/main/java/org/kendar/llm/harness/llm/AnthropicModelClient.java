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

/** Anthropic Messages API over plain HTTP (non-streaming), keeping messages as raw JSON. */

public class AnthropicModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicModelClient.class);
    private static final String API_VERSION = "2023-06-01";

    private final AnthropicSettings props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    /** Tool whose JSON results are re-sent to the API as citable search_result blocks. */
    static final String RAG_TOOL = "search_local_knowledge";

    private final long backoffBaseMs;

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
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new ModelException("Anthropic API key (ANTHROPIC_API_KEY) is not configured");
        }
        ObjectNode body = buildBody(req);
        JsonNode json = send(body, cancel);

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
        body.set("messages", props.citations() ? withSearchResults(req.messages()) : req.messages());
        return body;
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

    private JsonNode send(ObjectNode body, CancelToken cancel) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new ModelException("Cannot serialize request", e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + "/v1/messages"))
                .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("content-type", "application/json")
                .header("x-api-key", props.apiKey())
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        int attempt = 0;
        while (true) {
            try {
                HttpResponse<String> resp = cancel.send(http, request);
                int status = resp.statusCode();
                if (status == 200) {
                    return mapper.readTree(resp.body());
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
                cancel.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted while calling the model", e);
            } catch (IOException e) {
                if (attempt >= props.maxRetries()) {
                    throw new ModelException("Anthropic API call failed: " + e.getMessage(), e);
                }
                attempt++;
                try {
                    cancel.sleep(Math.min(30_000L, backoffBaseMs << attempt));
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
        return s != null && s.length() > 1000 ? s.substring(0, 1000) + "…" : s;
    }
}

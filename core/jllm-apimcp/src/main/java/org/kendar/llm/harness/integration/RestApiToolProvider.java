package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.ToolFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import org.kendar.llm.harness.auth.Identity;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the operations of an OpenAPI spec that match {@code allowedPaths} into harness tools named
 * {@code <api>__<operationId>}. GET and HEAD operations are read-only.
 */
public final class RestApiToolProvider {

    private static final Logger log = LoggerFactory.getLogger(RestApiToolProvider.class);
    private static final int MAX_RESPONSE_CHARS = 20_000;

    private RestApiToolProvider() {
    }

    /** Never throws: an unreadable spec is logged and contributes no tools. */
    public static List<HarnessTool> load(RestApiSettings api, HttpClient http, ObjectMapper mapper) {
        if (!api.active()) return new ArrayList<>();
        try {
            return discover(api, http, mapper);
        } catch (Exception e) {
            log.warn("REST API '{}' skipped: {}", api.name(), e.toString());
            return new ArrayList<>();
        }
    }

    /** Reads the spec and builds the tools of the allowed operations. */
    public static List<HarnessTool> discover(RestApiSettings api, HttpClient http, ObjectMapper mapper) {
        return discover(api, http, mapper, Identity.ANONYMOUS);
    }

    /** As {@link #discover(RestApiSettings, HttpClient, ObjectMapper)}; each call uses the credentials of its chat's principal. */
    public static List<HarnessTool> discover(RestApiSettings api, HttpClient http, ObjectMapper mapper, Identity identity) {
        List<HarnessTool> out = new ArrayList<>();
        OpenAPI spec = readSpec(api);
        String baseUrl = baseUrl(api, spec);
        List<String> allow = ToolFilter.restKeys(api.allowedPaths());
        List<String> deny = ToolFilter.restKeys(api.denyPaths());
        if (spec.getPaths() != null) {
            spec.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
                if (!ToolFilter.visible(allow, deny, method + " " + path)) return;
                out.add(tool(api, baseUrl, path, method, item, op, http, mapper, identity));
            }));
        }
        if (out.isEmpty()) {
            log.warn("REST API '{}': no operation passes allowedPaths {} and denyPaths {}", api.name(),
                    api.allowedPaths(), api.denyPaths());
        }
        return out;
    }

    /** Base URL the tools call: the settings value, else the first server of the spec. */
    public static String resolveBaseUrl(RestApiSettings api) {
        return baseUrl(api, readSpec(api));
    }

    private static OpenAPI readSpec(RestApiSettings api) {
        String location = api.openApiUrl() != null ? api.openApiUrl() : api.openApiFile();
        if (location == null) throw new IllegalArgumentException("openApiUrl or openApiFile is required");
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        List<AuthorizationValue> auth = new ArrayList<>();
        api.headers().forEach((k, v) -> auth.add(new AuthorizationValue(k, v, "header")));
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(location, auth, options);
        if (result.getOpenAPI() == null) {
            throw new IllegalStateException("cannot parse " + location + ": " + result.getMessages());
        }
        return result.getOpenAPI();
    }

    private static String baseUrl(RestApiSettings api, OpenAPI spec) {
        String base = api.baseUrl();
        if (base == null && spec.getServers() != null && !spec.getServers().isEmpty()) {
            base = spec.getServers().get(0).getUrl();
        }
        if (base == null) throw new IllegalArgumentException("no baseUrl and the spec has no servers");
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static HarnessTool tool(RestApiSettings api, String baseUrl, String path, PathItem.HttpMethod method,
                                    PathItem item, Operation op, HttpClient http, ObjectMapper mapper,
                                    Identity identity) {
        List<Parameter> params = new ArrayList<>();
        if (item.getParameters() != null) params.addAll(item.getParameters());
        if (op.getParameters() != null) params.addAll(op.getParameters());

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Parameter p : params) {
            ObjectNode ps = p.getSchema() == null ? mapper.createObjectNode().put("type", "string")
                    : mapper.convertValue(p.getSchema(), ObjectNode.class);
            if (p.getDescription() != null) ps.put("description", p.getDescription());
            props.set(p.getName(), ps);
            if (Boolean.TRUE.equals(p.getRequired()) || "path".equals(p.getIn())) required.add(p.getName());
        }
        boolean hasBody = false;
        if (op.getRequestBody() != null) {
            MediaType json = jsonContent(op.getRequestBody().getContent());
            if (json != null) {
                hasBody = true;
                ObjectNode bs = json.getSchema() == null ? mapper.createObjectNode().put("type", "object")
                        : mapper.convertValue(json.getSchema(), ObjectNode.class);
                bs.put("description", "JSON request body");
                props.set("body", bs);
                if (Boolean.TRUE.equals(op.getRequestBody().getRequired())) required.add("body");
            }
        }
        if (required.isEmpty()) schema.remove("required");

        String opName = op.getOperationId() != null ? op.getOperationId() : method + "_" + path;
        String description = (op.getSummary() != null ? op.getSummary() : method + " " + path)
                + (op.getDescription() != null ? "\n" + op.getDescription() : "");
        boolean readOnly = method == PathItem.HttpMethod.GET || method == PathItem.HttpMethod.HEAD;
        boolean body = hasBody;
        return new SimpleTool(ToolNames.of(api.name() + "__" + opName), description, schema, api.effectiveGroup(),
                readOnly ? ToolKind.READ_ONLY : ToolKind.OTHER,
                (ctx, input) -> call(api, baseUrl, path, method, params, body, input, http, mapper,
                        identity.callerOf(ctx == null ? null : ctx.chatId())));
    }

    private static MediaType jsonContent(Content content) {
        if (content == null) return null;
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            if (e.getKey().contains("json")) return e.getValue();
        }
        return null;
    }

    private static String call(RestApiSettings api, String baseUrl, String path, PathItem.HttpMethod method,
                               List<Parameter> params, boolean hasBody, JsonNode input, HttpClient http,
                               ObjectMapper mapper, Identity.Caller caller) throws Exception {
        String resolved = path;
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>(api.headers());
        headers.putAll(caller.headers("api:" + api.name()));
        for (Parameter p : params) {
            JsonNode v = input.get(p.getName());
            if (v == null || v.isNull()) continue;
            String text = v.isValueNode() ? v.asText() : v.toString();
            switch (p.getIn()) {
                case "path" -> resolved = resolved.replace("{" + p.getName() + "}", encode(text));
                case "query" -> query.put(p.getName(), text);
                case "header" -> headers.put(p.getName(), text);
                default -> { }
            }
        }
        StringBuilder url = new StringBuilder(baseUrl).append(resolved);
        char sep = '?';
        for (Map.Entry<String, String> q : query.entrySet()) {
            url.append(sep).append(encode(q.getKey())).append('=').append(encode(q.getValue()));
            sep = '&';
        }
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (hasBody && input.hasNonNull("body")) {
            publisher = HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input.get("body")));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(60))
                .method(method.name(), publisher);
        if (hasBody) b.header("Content-Type", "application/json");
        headers.forEach(b::header);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String text = resp.body();
        if (text.length() > MAX_RESPONSE_CHARS) text = text.substring(0, MAX_RESPONSE_CHARS) + "\n[truncated]";
        return "HTTP " + resp.statusCode() + "\n" + text;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

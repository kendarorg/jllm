package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.kendar.llm.harness.auth.Identity;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.ToolFilter;
import org.kendar.llm.harness.util.Wildcard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the operations of an OpenAPI spec that match {@code allowedPaths} into harness tools named
 * {@code <api>__<operationId>}. GET and HEAD operations are read-only.
 */
public final class RestApiToolProvider {

    private static final Logger log = LoggerFactory.getLogger(RestApiToolProvider.class);
    private static final int MAX_RESPONSE_CHARS = 20_000;
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]*)}");
    /**
     * Headers the model can never set: credentials, framing, and those OpenAPI says to ignore as header parameters
     * (Accept, Content-Type, Authorization). Lower case.
     */
    private static final Set<String> RESTRICTED_HEADERS = Set.of("authorization", "proxy-authorization", "host",
            "content-length", "connection", "expect", "upgrade", "transfer-encoding", "te", "trailer", "keep-alive",
            "proxy-connection", "content-type", "accept");

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
        return discover(api, http, mapper, identity, null);
    }

    /**
     * As {@link #discover(RestApiSettings, HttpClient, ObjectMapper, Identity)}; the names are also taken in
     * {@code claims} under the source {@code "api:<name>"}.
     *
     * @throws ToolNames.CollisionException when two operations get the same tool name, or one takes the name of a
     *                                      tool of another source of {@code claims}
     */
    public static List<HarnessTool> discover(RestApiSettings api, HttpClient http, ObjectMapper mapper, Identity identity,
                                             ToolNames.Claims claims) {
        List<HarnessTool> out = new ArrayList<>();
        OpenAPI spec = readSpec(api);
        String baseUrl = baseUrl(api, spec);
        Filter filter = new Filter(ToolFilter.restKeys(api.allowedPaths()), ToolFilter.restKeys(api.denyPaths()),
                spec.getPaths() == null ? List.of() : List.copyOf(spec.getPaths().keySet()));
        ObjectMapper schemas = spec.getSpecVersion() == SpecVersion.V31 ? Json31.mapper() : Json.mapper();
        ToolNames.SourceNames names = new ToolNames.SourceNames();
        if (spec.getPaths() != null) {
            spec.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
                if (!filter.visible(method + " " + path)) return;
                out.add(tool(api, baseUrl, path, method, item, op, http, mapper, schemas, identity, filter, names));
            }));
        }
        if (out.isEmpty()) {
            log.warn("REST API '{}': no operation passes allowedPaths {} and denyPaths {}", api.name(),
                    api.allowedPaths(), api.denyPaths());
        }
        if (claims != null) claims.claimAll("api:" + api.name(), names.names());
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

    /** A parameter of an operation and the property of the tool input that carries it. */
    private record Param(Parameter spec, String property) {
    }

    private static HarnessTool tool(RestApiSettings api, String baseUrl, String path, PathItem.HttpMethod method,
                                    PathItem item, Operation op, HttpClient http, ObjectMapper mapper,
                                    ObjectMapper schemas, Identity identity, Filter filter,
                                    ToolNames.SourceNames names) {
        // Path-level parameters first; an operation parameter with the same location and name replaces it.
        Map<String, Parameter> merged = new LinkedHashMap<>();
        if (item.getParameters() != null) item.getParameters().forEach(p -> merged.put(p.getIn() + ":" + p.getName(), p));
        if (op.getParameters() != null) op.getParameters().forEach(p -> merged.put(p.getIn() + ":" + p.getName(), p));
        Set<String> configured = lowerCase(api.headers().keySet());

        List<Param> params = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Parameter p : merged.values()) {
            if ("header".equals(p.getIn()) && !modelHeader(p.getName(), configured)) continue;
            String property = used.contains(p.getName()) ? p.getIn() + "_" + p.getName() : p.getName();
            used.add(property);
            params.add(new Param(p, property));
        }

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        Set<String> required = new LinkedHashSet<>();
        for (Param param : params) {
            Parameter p = param.spec();
            ObjectNode ps = p.getSchema() == null ? mapper.createObjectNode().put("type", "string")
                    : toSchema(schemas, p.getSchema());
            if (p.getDescription() != null) ps.put("description", p.getDescription());
            props.set(param.property(), ps);
            if (Boolean.TRUE.equals(p.getRequired()) || "path".equals(p.getIn())) required.add(param.property());
        }
        String bodyProperty = null;
        if (op.getRequestBody() != null) {
            MediaType json = jsonContent(op.getRequestBody().getContent());
            if (json != null) {
                bodyProperty = used.contains("body") ? "requestBody" : "body";
                while (used.contains(bodyProperty)) bodyProperty = bodyProperty + "_";
                ObjectNode bs = json.getSchema() == null ? mapper.createObjectNode().put("type", "object")
                        : toSchema(schemas, json.getSchema());
                bs.put("description", "JSON request body");
                props.set(bodyProperty, bs);
                if (Boolean.TRUE.equals(op.getRequestBody().getRequired())) required.add(bodyProperty);
            }
        }
        if (!required.isEmpty()) {
            ArrayNode req = schema.putArray("required");
            required.forEach(req::add);
        }

        String opName = op.getOperationId() != null ? op.getOperationId() : method + "_" + path;
        String name = names.add(api.name() + "__" + opName,
                "REST API '" + api.name() + "' operation " + method + " " + path);
        String description = (op.getSummary() != null ? op.getSummary() : method + " " + path)
                + (op.getDescription() != null ? "\n" + op.getDescription() : "");
        boolean readOnly = method == PathItem.HttpMethod.GET || method == PathItem.HttpMethod.HEAD;
        String body = bodyProperty;
        return new SimpleTool(name, description, schema, api.effectiveGroup(),
                readOnly ? ToolKind.READ_ONLY : ToolKind.OTHER,
                (ctx, input) -> call(api, baseUrl, path, method, params, body, input, http, mapper, filter,
                        identity.callerOf(ctx == null ? null : ctx.chatId())));
    }

    /** The JSON schema of a spec schema, without the null fields the plain mapper would write. */
    private static ObjectNode toSchema(ObjectMapper schemas, Object schema) {
        ObjectNode node = schemas.convertValue(schema, ObjectNode.class);
        stripNulls(node);
        return node;
    }

    static void stripNulls(JsonNode node) {
        if (node instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                if (it.next().getValue().isNull()) it.remove();
            }
            o.forEach(RestApiToolProvider::stripNulls);
        } else if (node instanceof ArrayNode a) {
            a.forEach(RestApiToolProvider::stripNulls);
        }
    }

    /** Whether the model may set a header parameter: not a restricted one, not one the settings configure. */
    private static boolean modelHeader(String name, Set<String> configured) {
        String n = name.toLowerCase(Locale.ROOT);
        return !RESTRICTED_HEADERS.contains(n) && !configured.contains(n);
    }

    private static Set<String> lowerCase(Collection<String> names) {
        Set<String> out = new HashSet<>();
        for (String n : names) out.add(n.toLowerCase(Locale.ROOT));
        return out;
    }

    private static MediaType jsonContent(Content content) {
        if (content == null) return null;
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            if (e.getKey().contains("json")) return e.getValue();
        }
        return null;
    }

    private static String call(RestApiSettings api, String baseUrl, String path, PathItem.HttpMethod method,
                               List<Param> params, String bodyProperty, JsonNode input, HttpClient http,
                               ObjectMapper mapper, Filter filter, Identity.Caller caller) throws Exception {
        String resolved = path;
        Map<String, String> query = new LinkedHashMap<>();
        // Configured headers win over the model's, whatever their case.
        Map<String, String> configured = new LinkedHashMap<>(api.headers());
        configured.putAll(caller.headers("api:" + api.name()));
        Set<String> configuredNames = lowerCase(configured.keySet());
        Map<String, String> headers = new LinkedHashMap<>();
        for (Param param : params) {
            Parameter p = param.spec();
            JsonNode v = input.get(param.property());
            if (v == null || v.isNull()) continue;
            String text = v.isValueNode() ? v.asText() : v.toString();
            switch (p.getIn()) {
                case "path" -> resolved = resolved.replace("{" + p.getName() + "}", encode(text));
                case "query" -> query.put(p.getName(), text);
                case "header" -> {
                    if (modelHeader(p.getName(), configuredNames)) headers.put(p.getName(), text);
                }
                default -> { }
            }
        }
        List<String> missing = new ArrayList<>();
        Matcher m = PATH_PARAM.matcher(resolved);
        while (m.find()) missing.add(m.group(1));
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing path parameter(s) " + String.join(", ", missing));
        }
        filter.checkResolved(method.name(), resolved);
        headers.putAll(configured);

        StringBuilder url = new StringBuilder(baseUrl).append(resolved);
        char sep = '?';
        for (Map.Entry<String, String> q : query.entrySet()) {
            url.append(sep).append(encode(q.getKey())).append('=').append(encode(q.getValue()));
            sep = '&';
        }
        URI uri = URI.create(url.toString()).normalize();
        String basePath = Objects.requireNonNullElse(URI.create(baseUrl).normalize().getRawPath(), "");
        String target = Objects.requireNonNullElse(uri.getRawPath(), "");
        boolean inside = basePath.isEmpty() || target.equals(basePath) || target.startsWith(basePath + "/");
        if (!inside || target.contains("/../") || target.endsWith("/..")) {
            throw new IllegalArgumentException("the path " + target + " leaves the API base path " + basePath);
        }
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (bodyProperty != null && input.hasNonNull(bodyProperty)) {
            publisher = HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input.get(bodyProperty)));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .method(method.name(), publisher);
        if (bodyProperty != null) b.header("Content-Type", "application/json");
        headers.forEach(b::header);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String text = resp.body();
        if (text.length() > MAX_RESPONSE_CHARS) text = text.substring(0, MAX_RESPONSE_CHARS) + "\n[truncated]";
        return "HTTP " + resp.statusCode() + "\n" + text;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * {@code allowedPaths}/{@code denyPaths} of an API. Operations are checked by their template at discovery; a call
     * whose path parameters hold dot segments ({@code ..}) reaches another path, which is checked again after
     * normalization, against the literal path and every spec template it matches.
     */
    record Filter(List<String> allow, List<String> deny, List<String> templates) {

        boolean visible(String key) {
            return ToolFilter.visible(allow, deny, key);
        }

        /**
         * @param resolved the operation path with the path parameters substituted (percent-encoded)
         * @throws IllegalArgumentException when the normalized path leaves the base path or is not allowed
         */
        void checkResolved(String method, String resolved) {
            String decoded = URLDecoder.decode(resolved.replace("+", "%2B"), StandardCharsets.UTF_8);
            String[] segments = decoded.split("/", -1);
            boolean dots = false;
            for (String s : segments) dots |= s.equals(".") || s.equals("..");
            if (!dots) return;
            Deque<String> out = new ArrayDeque<>();
            for (int i = 0; i < segments.length; i++) {
                String s = segments[i];
                if (i == 0 && s.isEmpty()) continue;
                if (s.equals(".")) continue;
                if (s.equals("..")) {
                    if (out.isEmpty()) {
                        throw new IllegalArgumentException("the path " + decoded + " leaves the API base path");
                    }
                    out.removeLast();
                } else {
                    out.addLast(s);
                }
            }
            String normalized = "/" + String.join("/", out);
            List<String> keys = new ArrayList<>();
            keys.add(method + " " + normalized);
            for (String t : templates) if (templateMatches(t, normalized)) keys.add(method + " " + t);
            boolean allowed = allow == null || keys.stream().anyMatch(k -> Wildcard.matchesAny(allow, k));
            boolean denied = keys.stream().anyMatch(k -> Wildcard.matchesAny(deny, k));
            if (!allowed || denied) {
                throw new IllegalArgumentException("the path " + normalized + " (from " + decoded
                        + ") is not allowed by allowedPaths/denyPaths");
            }
        }

        static boolean templateMatches(String template, String path) {
            String[] t = template.split("/", -1);
            String[] p = path.split("/", -1);
            if (t.length != p.length) return false;
            for (int i = 0; i < t.length; i++) {
                if (!t[i].contains("{")) {
                    if (!t[i].equals(p[i])) return false;
                    continue;
                }
                StringBuilder regex = new StringBuilder();
                Matcher m = PATH_PARAM.matcher(t[i]);
                int last = 0;
                while (m.find()) {
                    regex.append(Pattern.quote(t[i].substring(last, m.start()))).append("[^/]+");
                    last = m.end();
                }
                regex.append(Pattern.quote(t[i].substring(last)));
                if (!p[i].matches(regex.toString())) return false;
            }
            return true;
        }
    }
}

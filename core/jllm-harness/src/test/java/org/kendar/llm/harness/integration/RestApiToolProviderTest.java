package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RestApiToolProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPEC = """
            openapi: 3.0.0
            info: {title: t, version: "1"}
            paths:
              /pets/{id}:
                parameters:
                  - {name: id, in: path, required: true, description: path level, schema: {type: integer}}
                get:
                  operationId: getPet
                  parameters:
                    - {name: id, in: path, required: true, description: op level, schema: {type: string}}
                    - {name: X-Trace, in: header, schema: {type: string}}
                    - {name: X-Key, in: header, schema: {type: string}}
                    - {name: Authorization, in: header, schema: {type: string}}
              /notes:
                post:
                  operationId: addNote
                  parameters:
                    - {name: body, in: query, schema: {type: string}}
                  requestBody:
                    content:
                      application/json:
                        schema: {type: object, properties: {text: {type: string}}}
              /admin/reset:
                get:
                  operationId: reset
            """;
    private static final String CLASH = """
            openapi: 3.0.0
            info: {title: t, version: "1"}
            paths:
              /a:
                get: {operationId: a.b}
              /b:
                get: {operationId: a_b}
            """;

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ToolContext ctx = new ToolContext("c", Path.of("."));

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec.yaml", ex -> reply(ex, SPEC, "application/yaml"));
        server.createContext("/clash.yaml", ex -> reply(ex, CLASH, "application/yaml"));
        server.createContext("/api", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var h = ex.getRequestHeaders();
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())
                    + " " + body + " trace=" + h.getFirst("X-Trace") + " key=" + h.get("X-Key")
                    + " auth=" + h.getFirst("Authorization"));
            reply(ex, "{\"ok\":true}", "application/json");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body, String type)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private List<HarnessTool> tools(List<String> allow, List<String> deny) {
        return RestApiToolProvider.discover(new RestApiSettings("t", base + "/api", base + "/spec.yaml", null,
                Map.of("X-Key", "cfg"), null, allow, null, deny, null), http, MAPPER);
    }

    private static HarnessTool named(List<HarnessTool> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void schemasHaveNoNullFields() {
        for (HarnessTool t : tools(null, null)) {
            assertNoNulls(t.inputSchema(), t.name());
            assertFalse(t.inputSchema().toString().contains("exampleSetFlag"), t.inputSchema().toString());
        }
    }

    private static void assertNoNulls(JsonNode node, String where) {
        node.fields().forEachRemaining(e -> {
            assertFalse(e.getValue().isNull(), where + ": null field " + e.getKey());
            assertNoNulls(e.getValue(), where);
        });
        if (node.isArray()) node.forEach(n -> assertNoNulls(n, where));
    }

    @Test
    void operationParametersOverridePathLevelOnesAndRequiredIsUnique() {
        JsonNode schema = named(tools(null, null), "t__getPet").inputSchema();
        assertEquals("op level", schema.path("properties").path("id").path("description").asText());
        assertEquals("string", schema.path("properties").path("id").path("type").asText());
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        assertEquals(List.of("id"), required);
    }

    @Test
    void restrictedAndConfiguredHeadersAreNotModelParameters() throws Exception {
        HarnessTool get = named(tools(null, null), "t__getPet");
        JsonNode props = get.inputSchema().path("properties");
        assertTrue(props.has("X-Trace"));
        assertFalse(props.has("X-Key"));
        assertFalse(props.has("Authorization"));

        get.execute(ctx, MAPPER.readTree("{\"id\":\"7\",\"X-Trace\":\"t1\",\"X-Key\":\"model\",\"x-key\":\"m2\","
                + "\"Authorization\":\"Bearer evil\"}"));
        assertEquals("GET /api/pets/7  trace=t1 key=[cfg] auth=null", requests.get(0));
    }

    @Test
    void parameterNamedBodyKeepsItsNameAndTheRequestBodyMoves() throws Exception {
        HarnessTool add = named(tools(null, null), "t__addNote");
        JsonNode props = add.inputSchema().path("properties");
        assertEquals("string", props.path("body").path("type").asText());
        assertEquals("object", props.path("requestBody").path("type").asText());

        add.execute(ctx, MAPPER.readTree("{\"body\":\"q\",\"requestBody\":{\"text\":\"hi\"}}"));
        assertEquals("POST /api/notes?body=q {\"text\":\"hi\"} trace=null key=[cfg] auth=null", requests.get(0));
    }

    @Test
    void missingPathParameterIsReported() {
        HarnessTool get = named(tools(null, null), "t__getPet");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> get.execute(ctx, MAPPER.readTree("{}")));
        assertEquals("missing path parameter(s) id", e.getMessage());
        assertTrue(requests.isEmpty());
    }

    @Test
    void dotSegmentsInPathParametersAreCheckedAgainstAllowAndDeny() throws Exception {
        HarnessTool allowed = named(tools(List.of("GET /pets/{id}"), null), "t__getPet");
        for (String id : new String[]{"../admin/reset", "..", "./../admin/reset"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> allowed.execute(ctx, MAPPER.createObjectNode().put("id", id)), id);
        }
        HarnessTool notDenied = named(tools(null, List.of("GET /admin/*")), "t__getPet");
        assertThrows(IllegalArgumentException.class,
                () -> notDenied.execute(ctx, MAPPER.createObjectNode().put("id", "../admin/reset")));
        assertTrue(requests.isEmpty());

        // Staying inside the allowed operations is fine.
        allowed.execute(ctx, MAPPER.createObjectNode().put("id", "../pets/5"));
        assertEquals(1, requests.size());
    }

    @Test
    void pathParametersCannotLeaveTheBasePath() {
        HarnessTool get = named(tools(null, null), "t__getPet");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> get.execute(ctx, MAPPER.createObjectNode().put("id", "../../../etc")));
        assertTrue(e.getMessage().contains("leaves"), e.getMessage());
        assertTrue(requests.isEmpty());
    }

    @Test
    void sameToolNameFromTwoOperationsFailsNamingBoth() {
        RestApiSettings api = new RestApiSettings("c", base + "/api", base + "/clash.yaml", null, null, null, null,
                null, null, null);
        ToolNames.CollisionException e = assertThrows(ToolNames.CollisionException.class,
                () -> RestApiToolProvider.discover(api, http, MAPPER));
        assertTrue(e.getMessage().contains("GET /a"), e.getMessage());
        assertTrue(e.getMessage().contains("GET /b"), e.getMessage());
    }

    @Test
    void claimsDetectNamesTakenByAnotherSource() {
        ToolNames.Claims claims = new ToolNames.Claims();
        claims.claimAll("mcp:x", Map.of("t__reset", "MCP server 'x' tool 'reset'"));
        RestApiSettings api = new RestApiSettings("t", base + "/api", base + "/spec.yaml", null, null, null, null,
                null, null, null);
        assertThrows(ToolNames.CollisionException.class, () -> RestApiToolProvider.discover(api, http, MAPPER,
                org.kendar.llm.harness.auth.Identity.ANONYMOUS, claims));
    }
}

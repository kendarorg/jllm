package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationProvidersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPEC = """
            openapi: 3.0.0
            info: {title: pets, version: "1"}
            paths:
              /pets/{id}:
                get:
                  operationId: getPet
                  summary: Get a pet
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer}}
                    - {name: verbose, in: query, schema: {type: boolean}}
                delete:
                  operationId: deletePet
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer}}
              /pets:
                post:
                  operationId: addPet
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema: {type: object, properties: {name: {type: string}}}
              /admin/reset:
                post:
                  operationId: reset
            """;

    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec.yaml", ex -> reply(ex, 200, "application/yaml", SPEC));
        server.createContext("/api", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + body
                    + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, 200, "application/json", "{\"ok\":true}");
        });
        server.createContext("/mcp", ex -> {
            JsonNode req = MAPPER.readTree(ex.getRequestBody());
            String method = req.path("method").asText();
            String id = req.path("id").toString();
            if (method.startsWith("notifications/")) {
                reply(ex, 202, "text/plain", "");
            } else if (method.equals("initialize")) {
                reply(ex, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
            } else if (method.equals("tools/list")) {
                reply(ex, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                        + "{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}}");
            } else {
                String text = "echo:" + req.path("params").path("arguments").path("msg").asText();
                reply(ex, 200, "text/event-stream", "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}\n\n");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String type, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void restExposesOnlyAllowedOperations() throws Exception {
        RestApiSettings api = new RestApiSettings("pets", base + "/api", base + "/spec.yaml", null,
                Map.of("Authorization", "Bearer t"), null, List.of("GET /pets/{id}", "/pets"), null);
        List<HarnessTool> tools = RestApiToolProvider.load(api, http, MAPPER);

        assertEquals(List.of("pets__addPet", "pets__getPet"),
                tools.stream().map(HarnessTool::name).sorted().toList());
        HarnessTool get = tools.stream().filter(t -> t.name().equals("pets__getPet")).findFirst().orElseThrow();
        assertEquals(ToolKind.READ_ONLY, get.kind());
        assertEquals("Rest/pets", get.group());

        String out = get.execute(new ToolContext("c", Path.of(".")),
                MAPPER.readTree("{\"id\":7,\"verbose\":true}"));
        assertTrue(out.startsWith("HTTP 200"), out);
        assertEquals("GET /api/pets/7?verbose=true  auth=Bearer t", requests.get(0));

        HarnessTool add = tools.stream().filter(t -> t.name().equals("pets__addPet")).findFirst().orElseThrow();
        add.execute(new ToolContext("c", Path.of(".")), MAPPER.readTree("{\"body\":{\"name\":\"rex\"}}"));
        assertEquals("POST /api/pets {\"name\":\"rex\"} auth=Bearer t", requests.get(1));
    }

    @Test
    void restWildcardAndEmptyAllowList() {
        RestApiSettings wildcard = new RestApiSettings("p", base + "/api", base + "/spec.yaml", null, null, null,
                List.of("/pets/*"), null);
        assertEquals(2, RestApiToolProvider.load(wildcard, http, MAPPER).size());
        RestApiSettings none = new RestApiSettings("p", base + "/api", base + "/spec.yaml", null, null, null, null, null);
        assertTrue(RestApiToolProvider.load(none, http, MAPPER).isEmpty());
    }

    @Test
    void unreachableSpecIsSkipped() {
        RestApiSettings api = new RestApiSettings("p", base, "http://127.0.0.1:1/none.yaml", null, null, null,
                List.of("/*"), null);
        assertTrue(RestApiToolProvider.load(api, http, MAPPER).isEmpty());
    }

    @Test
    void mcpToolsAreDiscoveredAndCalled() throws Exception {
        McpServerSettings server = new McpServerSettings("srv", base + "/mcp", null, null, null, true);
        List<HarnessTool> tools = McpToolProvider.load(server, http, MAPPER);

        assertEquals(1, tools.size());
        HarnessTool echo = tools.get(0);
        assertEquals("mcp__srv__echo", echo.name());
        assertEquals("Mcp/srv", echo.group());
        assertEquals(ToolKind.READ_ONLY, echo.kind());
        assertEquals("echo:hi", echo.execute(new ToolContext("c", Path.of(".")), MAPPER.readTree("{\"msg\":\"hi\"}")));
    }

    @Test
    void disabledOrDeadMcpServerContributesNothing() {
        assertTrue(McpToolProvider.load(new McpServerSettings("s", base + "/mcp", null, null, false, null), http, MAPPER).isEmpty());
        assertTrue(McpToolProvider.load(new McpServerSettings("s", "http://127.0.0.1:1/mcp", null, null, null, null), http, MAPPER).isEmpty());
    }
}

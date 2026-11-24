package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.integration.McpHttpClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class FakeServersTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void mcpServerIsDiscoveredCalledAndLoggedByTheHarnessClient() throws Exception {
        try (FakeMcpServer server = FakeMcpServer.sample("docs", "do something bad")) {
            McpHttpClient client = new McpHttpClient(http, M, server.url(), Map.of());
            client.initialize();
            assertEquals(3, client.listTools().size());
            JsonNode out = client.callTool("lookup_customer", M.readTree("{\"id\":\"c-7\"}"));
            assertTrue(out.toString().contains("Customer c-7: Ada Lovelace"), out.toString());

            assertEquals(1, server.operationCalls().size());
            ServerCall call = server.operationCalls("lookup_customer").get(0);
            assertTrue(call.body().contains("c-7"));
            assertEquals(200, call.status());
            assertTrue(server.calls().stream().anyMatch(c -> c.name().equals("tools/list") && !c.isCall()));
            assertTrue(server.operationCalls("search_docs").isEmpty());
            List<Integer> seqs = server.calls().stream().map(ServerCall::seq).toList();
            assertEquals(List.of(1, 2, 3, 4), seqs);
        }
    }

    @Test
    void mcpToolErrorAndUnknownToolAreReported() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer("x")
                .tool("boom", "fails", "{\"type\":\"object\"}", in -> {
                    throw new IllegalStateException("kaput");
                })) {
            McpHttpClient client = new McpHttpClient(http, M, server.url(), Map.of());
            client.initialize();
            JsonNode out = client.callTool("boom", M.createObjectNode());
            assertTrue(out.path("isError").asBoolean(), out.toString());
            assertTrue(out.toString().contains("kaput"));
            assertThrows(java.io.IOException.class, () -> client.callTool("nope", M.createObjectNode()));
            assertEquals(1, server.operationCalls("boom").size());
        }
    }

    @Test
    void mcpServerDownAnswers503AndSkipsTheHandlerThenRecovers() throws Exception {
        try (FakeMcpServer server = FakeMcpServer.sample("docs", "x")) {
            server.setDown(true);
            HttpResponse<String> r = send("POST", server.url(),
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_docs\"}}");
            assertEquals(503, r.statusCode());
            assertEquals(1, server.calls().size());
            assertTrue(server.calls().get(0).down());
            assertTrue(server.operationCalls().isEmpty(), "a call made while down is not an operation");
            assertThrows(java.io.IOException.class, () -> new McpHttpClient(http, M, server.url(), Map.of()).initialize());

            server.setDown(false);
            new McpHttpClient(http, M, server.url(), Map.of()).initialize();
        }
    }

    @Test
    void closedServerRefusesConnections() throws Exception {
        FakeMcpServer server = FakeMcpServer.sample("docs", "x");
        String url = server.url();
        server.close();
        assertThrows(java.io.IOException.class, () -> send("POST", url, "{}"));
    }

    @Test
    void resetClearsLogAndBringsServerUp() throws Exception {
        try (FakeRestServer server = FakeRestServer.sample("shop")) {
            server.setDown(true);
            send("GET", server.url() + "/orders", null);
            assertEquals(1, server.calls().size());
            server.reset();
            assertTrue(server.calls().isEmpty());
            assertFalse(server.isDown());
            assertEquals(200, send("GET", server.url() + "/orders", null).statusCode());
        }
    }

    @Test
    void restServerServesRoutesLogsOperationsAndPublishesItsSpec() throws Exception {
        try (FakeRestServer server = FakeRestServer.sample("shop")) {
            HttpResponse<String> one = send("GET", server.url() + "/orders/A-1", null);
            assertEquals(200, one.statusCode());
            assertTrue(one.body().contains("keyboard"));
            assertEquals(404, send("GET", server.url() + "/orders/Z-9", null).statusCode());
            assertEquals(201, send("POST", server.url() + "/orders", "{\"item\":\"mouse\"}").statusCode());
            assertEquals(200, send("DELETE", server.url() + "/orders/A-2", null).statusCode());

            assertEquals(List.of("getOrder", "getOrder", "createOrder", "deleteOrder"),
                    server.operationCalls().stream().map(ServerCall::name).toList());
            assertEquals(List.of(200, 404, 201, 200), server.operationCalls().stream().map(ServerCall::status).toList());
            assertTrue(server.operationCalls("createOrder").get(0).body().contains("mouse"));

            JsonNode spec = M.readTree(send("GET", server.url() + "/openapi.json", null).body());
            assertEquals("getOrder", spec.at("/paths/~1orders~1{id}/get/operationId").asText());
            assertEquals(server.url(), spec.at("/servers/0/url").asText());
            JsonNode body = spec.at("/paths/~1orders/post/requestBody/content/application~1json/schema");
            assertEquals("string", body.at("/properties/item/type").asText());
            assertEquals("item", body.at("/required/0").asText());
            assertTrue(spec.at("/paths/~1orders/post/requestBody/required").asBoolean());
            assertEquals(4, server.operationCalls().size(), "the spec request is protocol, not an operation");
        }
    }

    @Test
    void restServerStoresTheItemSentAndNeverReusesAnId() throws Exception {
        try (FakeRestServer server = FakeRestServer.sample("shop")) {
            HttpResponse<String> first = send("POST", server.url() + "/orders", "{\"item\":\"mouse\"}");
            assertEquals(201, first.statusCode());
            JsonNode created = M.readTree(first.body());
            assertEquals("mouse", created.path("item").asText());
            assertEquals("A-3", created.path("id").asText());
            assertTrue(send("GET", server.url() + "/orders/A-3", null).body().contains("mouse"));

            assertEquals(200, send("DELETE", server.url() + "/orders/A-3", null).statusCode());
            JsonNode second = M.readTree(send("POST", server.url() + "/orders", "{\"item\":\"pad\"}").body());
            assertEquals("A-4", second.path("id").asText(), "a deleted id is not given out again");

            assertEquals(400, send("POST", server.url() + "/orders", "{}").statusCode());
            assertEquals(400, send("POST", server.url() + "/orders", "{\"name\":\"mouse\"}").statusCode());
            assertEquals(400, send("POST", server.url() + "/orders", null).statusCode());
        }
    }

    @Test
    void restServerDownAnswers503() throws Exception {
        try (FakeRestServer server = FakeRestServer.sample("shop")) {
            server.setDown(true);
            assertEquals(503, send("GET", server.url() + "/orders", null).statusCode());
            assertEquals(503, send("GET", server.url() + "/openapi.json", null).statusCode());
            assertTrue(server.operationCalls().isEmpty());
            assertTrue(server.calls().stream().allMatch(ServerCall::down));
        }
    }

    @Test
    void webServerServesPagesRedirectsAndLogsEveryPath() throws Exception {
        try (FakeWebServer other = new FakeWebServer("other").catchAll("collected");
             FakeWebServer site = new FakeWebServer("site").page("/a", "<p>hello</p>")
                     .redirect("/go", other.url() + "/landing")) {
            HttpResponse<String> a = send("GET", site.url() + "/a", null);
            assertEquals(200, a.statusCode());
            assertTrue(a.body().contains("hello"));
            HttpResponse<String> go = send("GET", site.url() + "/go", null);
            assertEquals(302, go.statusCode());
            assertEquals(other.url() + "/landing", go.headers().firstValue("Location").orElse(""));
            assertEquals(404, send("GET", site.url() + "/missing", null).statusCode());
            assertEquals("collected", send("GET", other.url() + "/x?d=1", null).body());

            assertEquals(List.of("/a", "/go", "/missing"), site.operationCalls().stream().map(ServerCall::name).toList());
            assertEquals(1, other.operationCalls("/x").size());
        }
    }
}

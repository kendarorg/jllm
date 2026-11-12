package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.integration.McpHttpClient;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class UserIdentityTest {

    @TempDir
    Path base;

    private HttpServer server;
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = MAPPER.readTree(body);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            String method = req.path("method").asText();
            received.add(method + " auth=" + auth + " session=" + session);
            if (req.path("id").isMissingNode()) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            String reply;
            if ("initialize".equals(method)) {
                ex.getResponseHeaders().add("Mcp-Session-Id", "sess-" + auth);
                reply = "{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"result\":{}}";
            } else {
                String text = "auth=" + auth + " session=" + session;
                reply = "{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id")
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @Test
    void mcpCallsKeepOneSessionPerPrincipalAndSendOnlyItsOwnHeaders() throws Exception {
        McpHttpClient client = new McpHttpClient(HttpClient.newHttpClient(), MAPPER, url(), Map.of());
        JsonNode args = MAPPER.createObjectNode();

        String alice = client.callTool("t", args, "alice", Map.of("Authorization", "A")).path("content").get(0)
                .path("text").asText();
        String bob = client.callTool("t", args, "bob", Map.of("Authorization", "B")).path("content").get(0)
                .path("text").asText();
        String again = client.callTool("t", args, "alice", Map.of("Authorization", "A")).path("content").get(0)
                .path("text").asText();

        assertEquals("auth=A session=sess-A", alice);
        assertEquals("auth=B session=sess-B", bob);
        assertEquals("auth=A session=sess-A", again);
        // each principal initialized once: two initialize requests, not three
        long inits = received.stream().filter(r -> r.startsWith("initialize")).count();
        assertEquals(2, inits, received.toString());
    }

    @Test
    void mcpStaticHeadersStillSentWithoutPrincipal() throws Exception {
        McpHttpClient client = new McpHttpClient(HttpClient.newHttpClient(), MAPPER, url(),
                Map.of("Authorization", "static"));
        String text = client.callTool("t", MAPPER.createObjectNode()).path("content").get(0).path("text").asText();
        assertEquals("auth=static session=sess-static", text);
    }

    @Test
    void identityResolvesPrincipalHeadersAndPolicyPerChat() {
        Identity identity = new Identity(
                (user, source) -> user == null ? Map.of() : Map.of("Authorization", "for " + user.id() + " " + source),
                user -> user != null && user.roles().contains("reader")
                        ? new UserPolicy(t -> t.kind() == ToolKind.READ_ONLY, null) : UserPolicy.ALL,
                id -> new HarnessPrincipal(id, Set.of("reader"), Map.of()),
                chat -> "c1".equals(chat) ? "rita" : null);

        assertEquals(Map.of("Authorization", "for rita mcp:docs"), identity.callerOf("c1").headers("mcp:docs"));
        assertEquals(Map.of(), identity.callerOf("c2").headers("mcp:docs"));
        assertEquals("rita", identity.principalOf("c1").id());
        assertNull(identity.principalOf("c2"));
        SimpleTool write = new SimpleTool("w", "w", MAPPER.createObjectNode(), "g", ToolKind.OTHER, (c, i) -> "");
        assertFalse(identity.policyOf("c1").allows(write));
        assertTrue(identity.policyOf("c2").allows(write));
    }

    @Test
    void readerCannotCallWriteToolEvenWhenTheModelNamesIt() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        SimpleTool write = new SimpleTool("write_doc", "writes", MAPPER.createObjectNode(), "Misc", ToolKind.OTHER,
                (c, i) -> {
                    ran.add("write");
                    return "written";
                });
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "write_doc", "{}"), text("ok"));
        AgentHarness h = readerHarness(model, write);

        String chat = h.sessions().create("t", "rita").chatId();
        String out = output(run(h, chat));

        assertTrue(out.startsWith("Not available to this user") || out.startsWith("Unknown tool"), out);
        assertTrue(ran.isEmpty());
    }

    @Test
    void readerIsOfferedOnlyReadTools() throws Exception {
        SimpleTool read = new SimpleTool("read_doc", "reads", MAPPER.createObjectNode(), "Misc", ToolKind.READ_ONLY,
                (c, i) -> "");
        SimpleTool write = new SimpleTool("write_doc", "writes", MAPPER.createObjectNode(), "Misc", ToolKind.OTHER,
                (c, i) -> "");
        ScriptedModel model = new ScriptedModel().enqueue(text("ok"));
        AgentHarness h = readerHarness(model, read, write);

        String chat = h.sessions().create("t", "rita").chatId();
        run(h, chat);

        List<String> offered = model.requests.get(0).toolNames();
        assertTrue(offered.contains("read_doc"), offered.toString());
        assertFalse(offered.contains("write_doc"), offered.toString());
    }

    @Test
    void chatWithoutPrincipalKeepsEveryTool() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        SimpleTool write = new SimpleTool("write_doc", "writes", MAPPER.createObjectNode(), "Misc", ToolKind.READ_ONLY,
                (c, i) -> {
                    ran.add("write");
                    return "written";
                });
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "write_doc", "{}"), text("ok"));
        AgentHarness h = readerHarness(model, write);

        String out = output(run(h, h.sessions().create("t").chatId()));

        assertEquals("written", out);
        assertEquals(1, ran.size());
    }

    private AgentHarness readerHarness(ScriptedModel model, SimpleTool... tools) {
        return AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools()
                .tools(List.of(tools))
                .userPolicyResolver(user -> user != null && user.roles().contains("reader")
                        ? new UserPolicy(t -> t.kind() == ToolKind.READ_ONLY, null) : UserPolicy.ALL)
                .principalDirectory(id -> new HarnessPrincipal(id, Set.of("reader"), Map.of()))
                .build();
    }

    private List<AgentEvent> run(AgentHarness h, String chat) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(20, TimeUnit.SECONDS));
        return events;
    }

    private static String output(List<AgentEvent> events) {
        return events.stream().filter(e -> e.type().equals("tool_result")).findFirst().orElseThrow()
                .data().path("output").asText();
    }
}

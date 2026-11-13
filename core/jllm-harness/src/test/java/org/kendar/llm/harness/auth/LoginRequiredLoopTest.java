package org.kendar.llm.harness.auth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.RestApiSettings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** A REST API with OAuth inside the agent loop: the missing login is a tool result, never a changed tool list. */
class LoginRequiredLoopTest {

    @TempDir
    Path base;

    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/openapi.yaml", ex -> reply(ex, 200, """
                openapi: 3.0.0
                info: {title: t, version: "1"}
                servers: [{url: "%s/api"}]
                paths:
                  /items:
                    get: {operationId: listItems}
                """.formatted(url)));
        server.createContext("/api/", ex -> {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            reply(ex, "Bearer good".equals(a) ? 200 : 401, "{\"items\":[1]}");
        });
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void missingLoginIsAToolErrorWithOneEventAndTheToolListNeverChanges() throws Exception {
        RestApiSettings api = new RestApiSettings("crm", null, url + "/openapi.yaml", null, Map.of(), null, null,
                null, null, new RestApiSettings.OAuth(null, null, url + "/authorize", url + "/token", null, "cid",
                null, List.of("r"), null, null, null, null, null));
        TokenStore store = new FileTokenStore(base.resolve("tokens"));
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "crm__listItems", "{}"), toolUse("t2", "crm__listItems", "{}"), text("need login"),
                toolUse("t3", "crm__listItems", "{}"), text("done"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools()
                .settings(new HarnessSettings(null, List.of(api), null, null, null, null)).tokenStore(store).build();
        assertTrue(h.logins().containsKey("api:crm"));
        String chat = h.sessions().create("t").chatId();

        List<AgentEvent> first = run(h, chat, "list");
        List<AgentEvent> results = first.stream().filter(e -> e.type().equals("tool_result")).toList();
        assertEquals(2, results.size());
        String out = results.get(0).data().path("output").asText();
        assertTrue(out.contains("Not connected to api:crm") && out.contains("/login api:crm"), out);
        assertTrue(results.get(0).data().path("isError").asBoolean());
        List<AgentEvent> required = first.stream().filter(e -> e.type().equals("login_required")).toList();
        assertEquals(1, required.size(), "one event per run and source, the model retried");
        assertEquals("api:crm", required.get(0).data().path("source").asText());

        h.saveLogin(null, "api:crm", new OAuthToken("good", null, 0, null));
        List<AgentEvent> second = run(h, chat, "again");
        assertTrue(second.stream().filter(e -> e.type().equals("tool_result")).findFirst().orElseThrow()
                .data().path("output").asText().contains("HTTP 200"));
        assertTrue(second.stream().noneMatch(e -> e.type().equals("login_required")));

        // the tools offered never depended on the login: the prompt-cache prefix stays the same
        List<String> before = model.requests.get(0).toolNames();
        List<String> after = model.requests.get(model.requests.size() - 1).toolNames();
        assertEquals(before, after);
        assertTrue(before.contains("crm__listItems"));
        // no token text in any event
        for (AgentEvent e : second) assertFalse(e.data().toString().contains("Bearer good"), e.toString());

        assertTrue(h.logout(null, "api:crm"));
        assertTrue(store.get(null, "api:crm").isEmpty());
    }

    private final List<AgentEvent> all = Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.Semaphore finished = new java.util.concurrent.Semaphore(0);
    private boolean subscribed;

    /** The events of one run: one subscription for the whole test, cut at each run_finished. */
    private List<AgentEvent> run(AgentHarness h, String chat, String text) throws Exception {
        if (!subscribed) {
            subscribed = true;
            h.subscribe(chat, 0, ev -> {
                all.add(ev);
                if (ev.type().equals("run_finished")) finished.release();
            });
        }
        int from = all.size();
        h.start(chat, text);
        assertTrue(finished.tryAcquire(20, TimeUnit.SECONDS));
        synchronized (all) {
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }
}

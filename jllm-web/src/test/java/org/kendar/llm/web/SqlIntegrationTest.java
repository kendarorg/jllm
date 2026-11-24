package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.TokenCipher;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.web.RealHttpApp.Client;
import org.kendar.llm.web.sql.SqlStores;
import org.kendar.llm.web.sql.SqlUserRepository;
import org.kendar.llm.web.users.FileUserRepository;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real Spring Boot contexts on a random port with SQLite in a temp dir: no LLM, no Docker, localhost only. */
class SqlIntegrationTest {

    @TempDir
    Path home;

    private static void await(CyclicBarrier barrier, CountDownLatch arrived) {
        try {
            if (arrived != null) arrived.countDown();
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }

    @Test
    void contextPathGatesTheApiAndServesThePage() throws Exception {
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w", "--server.servlet.context-path=/jllm",
                "--jllm.web.enforce-password-change-on-loopback=true")) {
            Client cl = new Client(c);
            assertTrue(cl.base.endsWith("/jllm"), cl.base);
            int unauth = cl.send("GET", "/api/chat", null).statusCode();
            assertTrue(unauth == 401 || unauth == 403, "unauthenticated: " + unauth);
            assertEquals(401, cl.send("GET", "/api/me", null).statusCode());
            assertEquals(200, cl.send("GET", "/api/auth/config", null).statusCode());
            HttpResponse<String> index = cl.send("GET", "/", null);
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("<"), "an HTML page");
            assertEquals(401, cl.login("admin", "wrong"));
            assertEquals(200, cl.login("admin", "admin"));
            assertEquals(200, cl.send("GET", "/api/me", null).statusCode());
            HttpResponse<String> blocked = cl.send("GET", "/api/me/tools", null);
            assertEquals(403, blocked.statusCode(), blocked.body());
            assertEquals("password_change_required", RealHttpApp.JSON.readTree(blocked.body()).path("error").asText());
            // outside the context path nothing of the application answers
            String root = cl.base.substring(0, cl.base.length() - "/jllm".length());
            HttpResponse<String> outside = cl.http.send(HttpRequest.newBuilder(URI.create(root + "/api/chat")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, outside.statusCode());
        }
    }

    @Test
    void storeBeansFollowTheStoreSetting() throws Exception {
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w1")) {
            assertInstanceOf(SqlUserRepository.class, c.getBean(UserRepository.class));
            assertEquals(1, c.getBeansOfType(SqlStores.class).size());
            assertSame(c.getBean(SqlStores.class).chats(), c.getBean(ChatSessionStore.class));
        }
        assertTrue(Files.isRegularFile(home.resolve("jllm.db")));
        Files.delete(home.resolve("jllm.db"));
        // the starter is on the class path, jllm.store=file turns it off
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w2", "--jllm.store=file",
                "--jllm.webauth-dir=" + RealHttpApp.slashes(home.resolve("webauth")))) {
            assertInstanceOf(FileUserRepository.class, c.getBean(UserRepository.class));
            assertEquals(1, c.getBeansOfType(UserRepository.class).size());
            assertTrue(c.getBeansOfType(SqlStores.class).isEmpty());
            c.getBeanProvider(ChatSessionStore.class).forEach(st ->
                    assertFalse(st.getClass().getName().startsWith("org.kendar.llm.web.sql."), "no SQL chat store"));
        }
        assertFalse(Files.exists(home.resolve("jllm.db")), "no database touched in file mode");
    }

    @Test
    void twoInstancesShareOneDatabase() throws Exception {
        String key = TokenCipher.newKey();
        // the environment is read right before the database is opened and seeded: both instances wait there for each
        // other, so that they seed together (Spring's logging set-up is not thread safe: B starts once A is waiting)
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch aWaits = new CountDownLatch(1);
        Function<String, String> envA = k -> {
            await(barrier, aWaits);
            return "JLLM_TOKEN_KEY".equals(k) ? key : null;
        };
        Function<String, String> envB = k -> {
            await(barrier, null);
            return "JLLM_TOKEN_KEY".equals(k) ? key : null;
        };
        CompletableFuture<ConfigurableApplicationContext> fa = CompletableFuture.supplyAsync(() -> RealHttpApp.start(home, "a", envA));
        assertTrue(aWaits.await(30, TimeUnit.SECONDS));
        CompletableFuture<ConfigurableApplicationContext> fb = CompletableFuture.supplyAsync(() -> RealHttpApp.start(home, "b", envB));
        try (ConfigurableApplicationContext a = fa.get(); ConfigurableApplicationContext b = fb.get()) {
            assertEquals(3, a.getBean(UserRepository.class).all().size());
            assertEquals(3, b.getBean(UserRepository.class).all().size());
            CookieManager cookies = new CookieManager(); // one browser, signed in on A
            Client ca = new Client(a, cookies);
            Client cb = new Client(b, cookies);
            assertEquals(200, ca.login("admin", "admin"));
            assertEquals(200, cb.send("GET", "/api/me", null).statusCode(), "the sign-in session is shared");
            String chat = ca.createChat();
            assertTrue(cb.listed(chat), "chat of A listed by B");
            assertEquals(200, cb.send("GET", "/api/chat/" + chat, null).statusCode());
            String second = cb.createChat();
            assertTrue(ca.listed(second));
        }
    }

    @Test
    void fullFlowSurvivesARestartAndStaysPrivate() throws Exception {
        String chat;
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w1")) {
            Client cl = new Client(c);
            assertEquals(200, cl.login("admin", "admin"));
            HttpResponse<String> changed = cl.send("POST", "/api/me/password",
                    "{\"current\":\"admin\",\"next\":\"a-long-new-password\"}");
            assertEquals(204, changed.statusCode(), changed.body());
            chat = cl.createChat();
        }
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w2")) {
            Client cl = new Client(c);
            assertEquals(401, cl.login("admin", "admin"), "the old password is gone");
            assertEquals(200, cl.login("admin", "a-long-new-password"));
            assertTrue(cl.listed(chat));
            JsonNode got = RealHttpApp.JSON.readTree(cl.send("GET", "/api/chat/" + chat, null).body());
            assertEquals(chat, got.path("chatId").asText());

            Client other = new Client(c);
            assertEquals(200, other.login("poweru", "poweru"));
            int status = other.send("GET", "/api/chat/" + chat, null).statusCode();
            assertTrue(status == 404 || status == 403, "other user: " + status);
            assertFalse(other.listed(chat), "absent from the other user's list");
        }
    }

    @Test
    void tokenKeyGuardRefusesAnExposedServerWithoutAKey() {
        String[] exposed = {"--jllm.web.use-real-password=true", "--jllm.web.allow-unencrypted-tokens=false"};
        Function<String, String> none = k -> null;
        Exception e = assertThrows(Exception.class, () -> RealHttpApp.start(home, "w1", none, exposed).close());
        assertTrue(rootMessage(e).contains("JLLM_TOKEN_KEY"), rootMessage(e));
        String key = TokenCipher.newKey();
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w2", k -> "JLLM_TOKEN_KEY".equals(k) ? key : null, exposed)) {
            assertTrue(c.isRunning());
        }
    }

    @Test
    void securityHeadersOnARealResponse() throws Exception {
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w")) {
            Client cl = new Client(c);
            for (String path : new String[]{"/", "/api/chat", "/api/auth/config"}) {
                HttpResponse<String> r = cl.send("GET", path, null);
                String csp = r.headers().firstValue("Content-Security-Policy").orElse("");
                assertTrue(csp.contains("default-src 'self'") && csp.contains("frame-ancestors 'none'"), path + ": " + csp);
                assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null), path);
                assertTrue(r.headers().firstValue("Referrer-Policy").isPresent(), path);
            }
        }
    }
}

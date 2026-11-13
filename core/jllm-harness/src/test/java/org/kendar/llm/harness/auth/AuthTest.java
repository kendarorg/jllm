package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.integration.McpHttpClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    @Test
    void pkceChallengeMatchesRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        Pkce p = Pkce.generate();
        assertNotEquals(p.state(), p.verifier());
    }

    @Test
    void staticAndDynamicKeys() {
        assertEquals(Map.of("x-api-key", "k"), AuthProvider.staticKey("x-api-key", null, "k").headers());
        assertEquals(Map.of(), AuthProvider.staticKey("x", null, " ").headers());
        int[] n = {0};
        var d = AuthProvider.dynamicKey("authorization", "Bearer ", () -> "k" + (++n[0]));
        assertEquals("Bearer k1", d.headers().get("authorization"));
        assertEquals("Bearer k2", d.headers().get("authorization"));
    }

    @Test
    void tokenClientExchangesAndRefreshKeepsOldRefreshTokenWhenNotRotated() throws Exception {
        try (FakeOAuthServer s = new FakeOAuthServer()) {
            var c = new OAuthTokenClient(http, MAPPER, s.base() + "/token", "cid", null, () -> 1000);
            OAuthToken t = c.exchangeCode("code", "http://x/cb", "ver");
            assertEquals("access-1", t.access());
            assertEquals(4600, t.expiresAt());
            assertEquals("x", t.extra().get("scope"));
            assertEquals("ver", s.tokenRequests.get(0).get("code_verifier"));
            s.refreshError = "invalid_grant";
            assertEquals("invalid_grant", assertThrows(OAuthException.class, () -> c.refresh("r")).code());
        }
    }

    @Test
    void deviceFlowPollsThroughPendingAndSlowDown() throws Exception {
        try (FakeOAuthServer s = new FakeOAuthServer()) {
            s.devicePolls.add("authorization_pending");
            s.devicePolls.add("slow_down");
            var c = new OAuthTokenClient(http, MAPPER, s.base() + "/token", "cid", null);
            List<String> prompts = new ArrayList<>();
            List<Long> sleeps = new ArrayList<>();
            OAuthToken t = DeviceCodeFlow.run(http, MAPPER, s.base() + "/device", "cid", "scope", c,
                    d -> prompts.add(d.userCode()), sleeps::add, () -> 0);
            assertEquals("access-1", t.access());
            assertEquals(List.of("ABCD"), prompts);
            assertEquals(List.of(0L, 0L, 5000L), sleeps);
        }
    }

    @Test
    void deviceFlowStopsOnDenied() throws Exception {
        try (FakeOAuthServer s = new FakeOAuthServer()) {
            s.devicePolls.add("access_denied");
            var c = new OAuthTokenClient(http, MAPPER, s.base() + "/token", "cid", null);
            var e = assertThrows(OAuthException.class, () -> DeviceCodeFlow.run(http, MAPPER, s.base() + "/device", "cid",
                    null, c, d -> {
                    }, ms -> {
                    }, () -> 0));
            assertEquals("access_denied", e.code());
        }
    }

    @Test
    void loopbackServerReturnsCodeAndRejectsWrongState() throws Exception {
        try (LoopbackCallbackServer ok = new LoopbackCallbackServer(0, "st")) {
            get(ok.redirectUri() + "?state=st&code=abc");
            assertEquals("abc", ok.awaitCode(Duration.ofSeconds(5)));
        }
        try (LoopbackCallbackServer bad = new LoopbackCallbackServer(0, "st")) {
            // stray requests with a wrong or missing state are rejected and the real redirect still completes
            assertEquals(400, status(bad.redirectUri() + "?state=other&code=abc"));
            assertEquals(400, status(bad.redirectUri() + "?error=access_denied"));
            assertEquals(200, status(bad.redirectUri() + "?state=st&code=real"));
            assertEquals("real", bad.awaitCode(Duration.ofSeconds(5)));
        }
        try (LoopbackCallbackServer stray = new LoopbackCallbackServer(0, "st")) {
            get(stray.redirectUri() + "?state=other&code=abc");
            assertEquals("timeout", assertThrows(OAuthException.class, () -> stray.awaitCode(Duration.ofMillis(200))).code());
        }
        try (LoopbackCallbackServer denied = new LoopbackCallbackServer(0, "st")) {
            get(denied.redirectUri() + "?state=st&error=access_denied");
            assertEquals("access_denied", assertThrows(OAuthException.class, () -> denied.awaitCode(Duration.ofSeconds(5))).code());
        }
        try (LoopbackCallbackServer noCode = new LoopbackCallbackServer(0, "st")) {
            get(noCode.redirectUri() + "?state=st");
            assertEquals("invalid_response", assertThrows(OAuthException.class, () -> noCode.awaitCode(Duration.ofSeconds(5))).code());
        }
        try (LoopbackCallbackServer slow = new LoopbackCallbackServer(0, "st")) {
            assertEquals("timeout", assertThrows(OAuthException.class, () -> slow.awaitCode(Duration.ofMillis(50))).code());
        }
    }

    @Test
    void tokenStoreRoundTripsAndRemoves() {
        TokenStore store = FileTokenStore.ofFile(tmp.resolve("sub/auth.json"));
        assertTrue(store.get(null, "a").isEmpty());
        store.put(null, "a", new OAuthToken("acc", "ref", 5, Map.of("k", "v")));
        store.put(null, "b", new OAuthToken("acc2", null, 0, null));
        OAuthToken a = store.get(null, "a").orElseThrow();
        assertEquals("acc", a.access());
        assertEquals("ref", a.refresh());
        assertEquals("v", a.extra().get("k"));
        assertNull(store.get(null, "b").orElseThrow().refresh());
        assertEquals(List.of("a", "b"), store.names(null));
        assertTrue(store.remove(null, "a"));
        assertFalse(store.remove(null, "a"));
        assertFalse(a.toString().contains("acc"));
    }

    @Test
    void separateStoresOnTheSameFileDoNotOverlapTheirFileLocks() throws Exception {
        Files.createDirectories(tmp.resolve("x"));
        TokenStore a = FileTokenStore.ofFile(tmp.resolve("auth.json"));
        TokenStore b = FileTokenStore.ofFile(tmp.resolve("x").resolve("..").resolve("auth.json"));
        // nested across stores on one file: re-entrant instead of OverlappingFileLockException
        assertEquals(List.of("n"), a.locked(null, "n", () -> {
            b.put(null, "n", new OAuthToken("v", null, 0, null));
            return b.names(null);
        }));
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            TokenStore s = i % 2 == 0 ? a : b;
            String name = "t" + i;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        String v = "v" + j;
                        s.locked(null, name, () -> {
                            s.put(null, name, new OAuthToken(v, null, 0, null));
                            return s.get(null, name);
                        });
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Thread t : threads) t.join();
        assertEquals(List.of(), errors);
        assertEquals(9, a.names(null).size());
    }

    @Test
    void defaultStoreIsSharedPerFile() {
        String old = System.getProperty("jllm.home");
        System.setProperty("jllm.home", tmp.toString());
        try {
            assertSame(TokenStore.defaultStore(), TokenStore.defaultStore());
        } finally {
            if (old == null) System.clearProperty("jllm.home");
            else System.setProperty("jllm.home", old);
        }
    }

    @Test
    void refreshingProviderRefreshesAheadOfExpiryAndPersistsRotation() throws Exception {
        TokenStore store = FileTokenStore.ofFile(tmp.resolve("auth.json"));
        AtomicLong now = new AtomicLong(1000);
        int[] refreshes = {0};
        var p = new RefreshingAuthProvider(null, "prov", store, t -> {
            refreshes[0]++;
            return new OAuthToken("new-" + t.refresh(), "rot-" + refreshes[0], now.get() + 3600, null);
        }, t -> Map.of("authorization", "Bearer " + t.access()), now::get);
        store.put(null, "prov", new OAuthToken("old", "r0", 1030, null)); // inside the 60 s skew
        assertEquals("Bearer new-r0", p.headers().get("authorization"));
        assertEquals("rot-1", store.get(null, "prov").orElseThrow().refresh());
        p.headers();
        assertEquals(1, refreshes[0]); // fresh token: no second refresh
        p.onUnauthorized();
        assertEquals("Bearer new-rot-1", p.headers().get("authorization"));
        assertEquals(2, refreshes[0]);
    }

    @Test
    void refreshingProviderExplainsInvalidGrantAndMissingLogin() {
        TokenStore store = FileTokenStore.ofFile(tmp.resolve("auth.json"));
        var p = new RefreshingAuthProvider("prov", store, r -> {
            throw new OAuthException("invalid_grant", "revoked");
        });
        assertEquals("prov", assertThrows(LoginRequiredException.class, p::headers).source());
        assertTrue(assertThrows(LoginRequiredException.class, p::headers).getMessage().contains("/login prov"));
        store.put(null, "prov", new OAuthToken("a", "r", 1, null));
        assertTrue(assertThrows(LoginRequiredException.class, p::headers).getMessage().contains("/login prov"));
        assertTrue(store.get(null, "prov").isEmpty()); // a revoked token is dropped
    }

    @Test
    void authCodeLoginRunsTheWholeFlow() throws Exception {
        try (FakeOAuthServer s = new FakeOAuthServer()) {
            var tokens = new OAuthTokenClient(http, MAPPER, s.base() + "/token", "cid", null);
            var login = new AuthCodeLogin("prov", s.base() + "/authorize", tokens, "cid", "scope", Map.of("audience", "x"),
                    0, AuthCodeLogin.bearer());
            OAuthToken t = login.login(new UserInteraction() {
                @Override
                public void show(String m) {
                }

                @Override
                public String ask(String p) {
                    return null;
                }

                @Override
                public void openUrl(String url) {
                    // play the browser: follow the redirect the authorization server would send
                    Map<String, String> q = LoopbackCallbackServer.query(URI.create(url).getRawQuery());
                    assertEquals("S256", q.get("code_challenge_method"));
                    assertEquals("x", q.get("audience"));
                    try {
                        get(q.get("redirect_uri") + "?state=" + q.get("state") + "&code=the-code");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            assertEquals("access-1", t.access());
            assertEquals("the-code", s.tokenRequests.get(0).get("code"));
            assertNotNull(s.tokenRequests.get(0).get("code_verifier"));
        }
    }

    @Test
    void mcpOAuthDiscoversRegistersAndRetriesOn401() throws Exception {
        try (FakeOAuthServer s = new FakeOAuthServer()) {
            var login = new McpOAuthLogin("srv", s.base() + "/mcp", http, MAPPER);
            var meta = login.discover();
            assertEquals(s.base() + "/token", meta.tokenEndpoint());
            assertEquals("client-1", login.register(meta, "http://127.0.0.1:1/callback"));

            TokenStore store = FileTokenStore.ofFile(tmp.resolve("auth.json"));
            store.put(null, "mcp:srv", new OAuthToken("stale", "refresh-0", 0,
                    Map.of("client_id", "client-1", "token_endpoint", s.base() + "/token")));
            s.validAccess = "access-1"; // what the next refresh issues
            McpHttpClient mcp = new McpHttpClient(http, MAPPER, s.base() + "/mcp", Map.of())
                    .authProvider(login.authProvider(store, null));
            assertTrue(mcp.listTools().isEmpty());
            // the stale token got a 401, then a refresh, then success
            assertEquals("Bearer stale", s.mcpAuthHeaders.get(0));
            assertEquals("Bearer access-1", s.mcpAuthHeaders.get(s.mcpAuthHeaders.size() - 1));
            assertEquals("client-1", store.get(null, "mcp:srv").orElseThrow().extra().get("client_id")); // kept across refresh
        }
    }

    private void get(String url) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }
}

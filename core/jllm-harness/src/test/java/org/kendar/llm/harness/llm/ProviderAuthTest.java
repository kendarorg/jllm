package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.*;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.OAuthSettings;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Per-user provider credentials: resolution order, scope handling, refresh, validation. */
class ProviderAuthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE = "provider:p";

    @TempDir
    Path tmp;

    private HttpServer server;
    private String base;
    private final List<String> auths = new CopyOnWriteArrayList<>();
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();
    private TokenStore store;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v1/chat/completions", ex -> {
            String a = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            auths.add(a);
            boolean bad = rejected.contains(a);
            byte[] out = (bad ? "{\"error\":\"no\"}"
                    : "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(bad ? 401 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/token", ex -> {
            byte[] out = "{\"access_token\":\"new\",\"refresh_token\":\"r2\",\"expires_in\":3600}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        store = new FileTokenStore(tmp, null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ProviderSettings provider(String userAuth, OAuthSettings oauth, String key, Boolean fallback) {
        return new ProviderSettings("p", "zzz", base + "/v1", key, "m", 100, 0, 10, true, false, null, null, null,
                null, null, null, userAuth, oauth, fallback, null, null);
    }

    private ModelClient client(ProviderSettings p) {
        HarnessSettings settings = new HarnessSettings(null, null, null, null, null, null, null, null, null, null,
                null, List.of(p), null);
        OAuthSources oauth = OAuthSources.from(settings, store, HttpClient.newHttpClient(), MAPPER, true);
        return new ProviderCatalog(List.of(p), MAPPER, 100, oauth.resolver()).client("p");
    }

    private String call(ModelClient c, String user) {
        return user == null ? c.simpleComplete("s", "u", 10)
                : CallerScope.callAs(HarnessPrincipal.of(user), () -> c.simpleComplete("s", "u", 10));
    }

    private void token(String user, String access) {
        store.put(user, SOURCE, new OAuthToken(access, null, 0, Map.of()));
    }

    @Test
    void userTokenOverridesTheGlobalKeyAndUsersGetDistinctCredentials() {
        token("alice", "ta");
        token("bob", "tb");
        ModelClient c = client(provider("token", null, "g", true));
        call(c, "alice");
        call(c, "bob");
        assertEquals(List.of("Bearer ta", "Bearer tb"), auths);
    }

    @Test
    void withoutACredentialAndWithoutFallbackTheCallAsksToConnect() {
        ModelClient c = client(provider("token", null, "g", null));
        LoginRequiredException e = assertThrows(LoginRequiredException.class, () -> call(c, "carol"));
        assertEquals(SOURCE, e.source());
        assertTrue(auths.isEmpty(), "nothing was sent");
    }

    @Test
    void fallbackToTheGlobalKeyOnlyWhenAllowed() {
        ModelClient c = client(provider("token", null, "g", true));
        call(c, "carol");
        assertEquals(List.of("Bearer g"), auths);
    }

    @Test
    void aCallWithNoScopeUsesTheGlobalKeyOnly() {
        token("alice", "ta");
        ModelClient c = client(provider("token", null, "g", null));
        call(c, null);
        assertEquals(List.of("Bearer g"), auths);
        ModelClient noKey = client(provider("token", null, null, null));
        assertThrows(LoginRequiredException.class, () -> call(noKey, null));
    }

    @Test
    void unauthorizedRefreshesOnceAndNeverFallsBackToTheGlobalKey() {
        token("alice", "ta");
        rejected.add("Bearer ta");
        ModelClient c = client(provider("token", null, "g", true));
        assertThrows(ModelException.class, () -> call(c, "alice"));
        assertEquals(List.of("Bearer ta", "Bearer ta"), auths);
    }

    @Test
    void anExpiredOAuthTokenIsRefreshedBeforeTheCall() {
        OAuthSettings oauth = new OAuthSettings(null, null, null, base + "/token", null, "cid", null, null, null,
                null, null, null, null);
        store.put("alice", SOURCE, new OAuthToken("old", "r1", 1, Map.of("client_id", "cid",
                "token_endpoint", base + "/token")));
        ModelClient c = client(provider("oauth", oauth, null, null));
        call(c, "alice");
        assertEquals(List.of("Bearer new"), auths);
        assertEquals("new", store.get("alice", SOURCE).orElseThrow().access());
    }

    @Test
    void theScopeFollowsAWrappedHopAndFailsClosedOnAnUnwrappedOne() {
        token("alice", "ta");
        ModelClient c = client(provider("token", null, "g", null));
        CallerScope.runAs(HarnessPrincipal.of("alice"), () -> {
            try {
                CompletableFuture.supplyAsync(CallerScope.wrapSupplier(() -> c.simpleComplete("s", "u", 10))).get();
                // not wrapped: the pool thread has no scope, so the global key is used, never alice's token
                CompletableFuture.supplyAsync(() -> c.simpleComplete("s", "u", 10)).get();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertEquals(List.of("Bearer ta", "Bearer g"), auths);
    }

    @Test
    void anApiKeyOnlyProviderIsUnchanged() {
        ModelClient c = client(provider(null, null, "g", null));
        call(c, "alice");
        call(c, null);
        assertEquals(List.of("Bearer g", "Bearer g"), auths);
    }

    @Test
    void settingsAreValidated() {
        OAuthSettings oauth = new OAuthSettings(null, null, null, "http://x/token", null, "cid", null, null, null,
                null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> provider("bogus", null, "g", null));
        assertThrows(IllegalArgumentException.class, () -> provider("oauth", null, "g", null));
        assertThrows(IllegalArgumentException.class, () -> provider("token", oauth, "g", null));
        assertThrows(IllegalArgumentException.class, () -> provider(null, oauth, "g", null));
        assertThrows(IllegalArgumentException.class, () -> provider("token", null, null, true),
                "fallback without a global key");
        assertThrows(IllegalArgumentException.class, () -> provider(null, null, "g", true));
        assertDoesNotThrow(() -> provider("oauth", oauth, null, null));
    }

    @Test
    void atMostOneProviderIsMarkedFast() {
        ProviderSettings a = fast("a", true);
        ProviderSettings b = fast("b", true);
        assertThrows(IllegalArgumentException.class, () -> new ProviderCatalog(List.of(a, b), MAPPER, 100, null));
        assertEquals("a", new ProviderCatalog(List.of(a, fast("c", false)), MAPPER, 100, null).fast().name());
    }

    private static ProviderSettings fast(String name, boolean fast) {
        return new ProviderSettings(name, "zzz", "http://x/v1", "k", "m", 100, 0, 10, true, false, null, null,
                null, null, null, null, null, null, null, null, fast);
    }
}

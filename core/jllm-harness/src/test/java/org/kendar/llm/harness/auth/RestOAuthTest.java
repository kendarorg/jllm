package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.integration.RestApiToolProvider;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** REST APIs with OAuth, against a fake authorization server and API. */
class RestOAuthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    private HttpServer server;
    private String base;
    /** Access tokens the API accepts. */
    private final Set<String> valid = ConcurrentHashMap.newKeySet();
    private final List<Map<String, String>> tokenRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> apiAuth = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger issued = new AtomicInteger();
    private volatile String refreshError;
    private volatile int expiresIn = 3600;
    /** Delay of the token endpoint, to make refreshes overlap. */
    private volatile long tokenDelayMs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/issuer/.well-known/oauth-authorization-server", ex -> json(ex, 200,
                "{\"authorization_endpoint\":\"" + base + "/authorize\",\"token_endpoint\":\"" + base
                        + "/token\",\"device_authorization_endpoint\":\"" + base + "/device\"}"));
        server.createContext("/token", this::token);
        server.createContext("/openapi.yaml", ex -> text(ex, 200, spec()));
        server.createContext("/api/", ex -> {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            apiAuth.add(a);
            if (a == null || !a.startsWith("Bearer ") || !valid.contains(a.substring(7))) {
                json(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            json(ex, 200, "{\"auth\":\"" + a + "\"}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String spec() {
        return """
                openapi: 3.0.0
                info: {title: t, version: "1"}
                servers: [{url: "%s/api"}]
                components:
                  securitySchemes:
                    oa:
                      type: oauth2
                      flows:
                        authorizationCode:
                          authorizationUrl: %s/authorize
                          tokenUrl: %s/token
                          scopes: {read: r, write: w, admin: a}
                        clientCredentials:
                          tokenUrl: %s/token
                          scopes: {svc: s}
                paths:
                  /items:
                    get:
                      operationId: listItems
                      security: [{oa: [read]}]
                  /admin:
                    get:
                      operationId: admin
                      security: [{oa: [admin]}]
                """.formatted(base, base, base, base);
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> f = new HashMap<>();
        for (String p : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            int i = p.indexOf('=');
            if (i > 0) f.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        tokenRequests.add(f);
        if (tokenDelayMs > 0) {
            try {
                Thread.sleep(tokenDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if ("refresh_token".equals(f.get("grant_type")) && refreshError != null) {
            json(ex, 400, "{\"error\":\"" + refreshError + "\"}");
            return;
        }
        int n = issued.incrementAndGet();
        String access = "acc-" + n;
        valid.add(access);
        String refresh = "client_credentials".equals(f.get("grant_type")) ? "" : ",\"refresh_token\":\"ref-" + n + "\"";
        json(ex, 200, "{\"access_token\":\"" + access + "\"" + refresh + ",\"expires_in\":" + expiresIn + "}");
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static void text(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/yaml");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ---- helpers

    private RestApiSettings api(RestApiSettings.OAuth oauth) {
        return new RestApiSettings("crm", null, base + "/openapi.yaml", null, Map.of(), null, null, null, null, oauth);
    }

    private static RestApiSettings.OAuth oauth(String flow, String issuer, String tokenUrl, Boolean perUser) {
        return new RestApiSettings.OAuth(flow, issuer, null, tokenUrl, null, "cid", null, null, null, perUser, null,
                null, null);
    }

    private record Setup(RestApiSettings api, OAuthCredentialResolver resolver, Identity identity, TokenStore store,
                         List<HarnessTool> tools) {
        HarnessTool tool(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        }
    }

    /** Chat ids are principal ids; "anon" has none. */
    private Setup setup(RestApiSettings api, TokenStore store) {
        RestOAuthLogin login = new RestOAuthLogin(api, http, MAPPER);
        OAuthCredentialResolver.Source src = login.clientCredentials()
                ? new OAuthCredentialResolver.Source(api.source(), login, false, login::clientCredentialsToken, login::header)
                : new OAuthCredentialResolver.Source(api.source(), login, login.perUser(), null, null);
        OAuthCredentialResolver resolver = new OAuthCredentialResolver(store, List.of(src));
        Identity identity = new Identity(CredentialResolver.chain((u, s) -> Map.of("Authorization", "from-file"),
                resolver), null, null, chat -> "anon".equals(chat) ? null : chat);
        List<HarnessTool> tools = RestApiToolProvider.discover(api, http, MAPPER, identity, null);
        return new Setup(api, resolver, identity, store, tools);
    }

    private static String call(HarnessTool tool, String chat) throws Exception {
        return tool.execute(new ToolContext(chat, Path.of(".")), MAPPER.createObjectNode());
    }

    private TokenStore store() {
        return new FileTokenStore(tmp.resolve("tokens"));
    }

    private void login(TokenStore store, String principal, String access, String refresh, long expiresAt) {
        valid.add(access);
        store.put(principal, "api:crm", new OAuthToken(access, refresh, expiresAt,
                Map.of("client_id", "cid", "token_endpoint", base + "/token")));
    }

    // ---- tests

    @Test
    void eachPrincipalCallsWithItsOwnTokenAndTheOAuthHeaderWins() throws Exception {
        TokenStore store = store();
        Setup s = setup(api(oauth(null, null, base + "/token", null)), store);
        login(store, "alice", "tok-a", null, 0);
        login(store, "bob", "tok-b", null, 0);
        assertTrue(call(s.tool("crm__listItems"), "alice").contains("Bearer tok-a"));
        assertTrue(call(s.tool("crm__listItems"), "bob").contains("Bearer tok-b"));
        // the user-file "Authorization" was replaced, not sent twice
        assertFalse(apiAuth.contains("from-file"));
        // a user without a login gets login required, the tool stays listed
        LoginRequiredException e = assertThrows(LoginRequiredException.class, () -> call(s.tool("crm__listItems"), "carol"));
        assertEquals("api:crm", e.source());
        assertTrue(e.getMessage().contains("/login api:crm"));
    }

    @Test
    void sharedLoginIsUsedByEveryUser() throws Exception {
        TokenStore store = store();
        Setup s = setup(api(oauth(null, null, base + "/token", false)), store);
        login(store, null, "tok-shared", null, 0);
        assertTrue(call(s.tool("crm__listItems"), "alice").contains("Bearer tok-shared"));
        assertTrue(call(s.tool("crm__listItems"), "bob").contains("Bearer tok-shared"));
    }

    @Test
    void clientCredentialsTokenIsCachedAndRenewedBeforeExpiry() throws Exception {
        expiresIn = 30; // inside the 60 s skew: every call renews
        Setup s = setup(api(oauth("client_credentials", null, base + "/token", null)), store());
        call(s.tool("crm__listItems"), "alice");
        call(s.tool("crm__listItems"), "bob");
        assertEquals(2, tokenRequests.size());
        expiresIn = 3600;
        tokenRequests.clear();
        Setup cached = setup(api(oauth("client_credentials", null, base + "/token", null)), store());
        call(cached.tool("crm__listItems"), "alice");
        call(cached.tool("crm__listItems"), "bob");
        assertEquals(1, tokenRequests.size());
        assertEquals("client_credentials", tokenRequests.get(0).get("grant_type"));
        assertEquals("svc", tokenRequests.get(0).get("scope")); // default scopes from the spec's scheme
    }

    @Test
    void expiringTokenIsRefreshedOnceUnderConcurrentCallsAndTheRotatedTokenIsSavedFirst() throws Exception {
        TokenStore store = store();
        Setup s = setup(api(oauth(null, null, base + "/token", null)), store);
        login(store, "alice", "old", "ref-0", 1); // long expired
        tokenDelayMs = 200;
        CountDownLatch go = new CountDownLatch(1);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    results.add(call(s.tool("crm__listItems"), "alice"));
                } catch (Exception e) {
                    results.add("error " + e);
                }
            }));
        }
        go.countDown();
        for (Thread t : threads) t.join();
        assertEquals(1, tokenRequests.size(), tokenRequests.toString());
        assertTrue(results.stream().allMatch(r -> r.contains("Bearer acc-1")), results.toString());
        OAuthToken saved = store.get("alice", "api:crm").orElseThrow();
        assertEquals("ref-1", saved.refresh());
        assertEquals("cid", saved.extra().get("client_id"));
    }

    @Test
    void invalidGrantDropsTheTokenAndAsksForALogin() {
        TokenStore store = store();
        Setup s = setup(api(oauth(null, null, base + "/token", null)), store);
        login(store, "alice", "old", "ref-0", 1);
        refreshError = "invalid_grant";
        assertThrows(LoginRequiredException.class, () -> call(s.tool("crm__listItems"), "alice"));
        assertTrue(store.get("alice", "api:crm").isEmpty());
    }

    @Test
    void unauthorizedRefreshesAndRetriesOnceThenAsksForALogin() throws Exception {
        TokenStore store = store();
        Setup s = setup(api(oauth(null, null, base + "/token", null)), store);
        login(store, "alice", "revoked", "ref-0", 0);
        valid.remove("revoked");
        assertTrue(call(s.tool("crm__listItems"), "alice").contains("Bearer acc-1"));
        assertEquals(List.of("Bearer revoked", "Bearer acc-1"), apiAuth);

        // without a refresh token the retry has nothing new: login required
        login(store, "bob", "dead", null, 0);
        valid.remove("dead");
        assertThrows(LoginRequiredException.class, () -> call(s.tool("crm__listItems"), "bob"));
    }

    @Test
    void endpointsComeFromTheIssuerOrTheSpecAndToolsSayTheyNeedALogin() {
        RestOAuthLogin fromIssuer = new RestOAuthLogin(api(oauth(null, base + "/issuer", null, null)), http, MAPPER);
        assertEquals(base + "/authorize", fromIssuer.endpoints().authorization());
        assertEquals(base + "/device", fromIssuer.endpoints().device());
        RestOAuthLogin fromSpec = new RestOAuthLogin(api(oauth(null, null, null, null)), http, MAPPER);
        assertEquals(base + "/token", fromSpec.endpoints().token());
        assertEquals(List.of("read", "admin"), fromSpec.endpoints().scopes());
        RestApiSettings filtered = new RestApiSettings("crm", null, base + "/openapi.yaml", null, Map.of(), null,
                List.of("GET /items"), null, null, oauth(null, null, null, null));
        assertEquals(List.of("read"), new RestOAuthLogin(filtered, http, MAPPER).endpoints().scopes());

        Setup s = setup(api(oauth(null, null, base + "/token", null)), store());
        assertTrue(s.tool("crm__listItems").description().endsWith("Needs the user's crm login."));
        Setup svc = setup(api(oauth("client_credentials", null, base + "/token", null)), store());
        assertFalse(svc.tool("crm__listItems").description().contains("Needs the user's"));
    }

    @Test
    void anApiWithNoEndpointAnywhereFailsAtStartup() {
        RestApiSettings none = new RestApiSettings("crm", base + "/api", null, tmp.resolve("missing.yaml").toString(),
                Map.of(), null, null, null, null, oauth(null, null, null, null));
        var settings = new org.kendar.llm.harness.settings.HarnessSettings(null, List.of(none), null, null, null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OAuthSources.from(settings, store(), http, MAPPER, false));
        assertTrue(e.getMessage().contains("REST API 'crm'"), e.getMessage());
    }

    @Test
    void settingsValidation() {
        assertThrows(IllegalArgumentException.class, () -> api(oauth("implicit", null, "x", null)));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> api(oauth("client_credentials", null, "x", true))).getMessage().contains("perUser"));
        assertFalse(oauth("client_credentials", null, "x", null).effectivePerUser());
        assertTrue(oauth(null, null, "x", null).effectivePerUser());
    }

    @Test
    void authorizationCodeLoginStoresClientAndEndpointForRefreshes() {
        RestOAuthLogin login = new RestOAuthLogin(api(oauth(null, base + "/issuer", null, null)), http, MAPPER);
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
                Map<String, String> q = LoopbackCallbackServer.query(URI.create(url).getRawQuery());
                assertEquals("cid", q.get("client_id"));
                assertEquals("read admin", q.get("scope"));
                try {
                    http.send(HttpRequest.newBuilder(URI.create(q.get("redirect_uri") + "?state=" + q.get("state")
                            + "&code=c1")).GET().build(), HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        assertEquals("acc-1", t.access());
        assertEquals("cid", t.extra().get("client_id"));
        assertEquals(base + "/token", t.extra().get("token_endpoint"));
        assertEquals("c1", tokenRequests.get(0).get("code"));
    }

    @Test
    void resolverKeepsTokensOutOfItsToStringAndLostCompareUsesTheStoredToken() {
        TokenStore store = store();
        AtomicLong now = new AtomicLong(1000);
        store.put(null, "p", new OAuthToken("a0", "r0", 1010, null));
        // another refresher stores a fresh token while this one is refreshing
        RefreshingAuthProvider p = new RefreshingAuthProvider(null, "p", store, cur -> {
            store.put(null, "p", new OAuthToken("theirs", "r-theirs", 9999, null));
            return new OAuthToken("mine", "r-mine", 9999, null);
        }, t -> Map.of("authorization", "Bearer " + t.access()), now::get);
        assertEquals("Bearer theirs", p.headers().get("authorization"));
        assertEquals("r-theirs", store.get(null, "p").orElseThrow().refresh());
        assertFalse(store.get(null, "p").orElseThrow().toString().contains("theirs"));
    }
}

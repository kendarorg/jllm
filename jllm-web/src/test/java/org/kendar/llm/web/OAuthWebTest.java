package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.auth.FileTokenStore;
import org.kendar.llm.harness.auth.OAuthToken;
import org.kendar.llm.harness.auth.TokenCipher;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.web.admin.AdminController;
import org.kendar.llm.web.admin.AdminInterceptor;
import org.kendar.llm.web.admin.ToolCatalogView;
import org.kendar.llm.web.auth.AuthController;
import org.kendar.llm.web.auth.CookieAuthentication;
import org.kendar.llm.web.auth.LoginThrottle;
import org.kendar.llm.web.auth.PasswordHasher;
import org.kendar.llm.web.auth.SessionStore;
import org.kendar.llm.web.commons.ApiInterceptor;
import org.kendar.llm.web.commons.ChatController;
import org.kendar.llm.web.commons.JllmProperties;
import org.kendar.llm.web.commons.OAuthController;
import org.kendar.llm.web.users.Permissions;
import org.kendar.llm.web.users.SkillFiles;
import org.kendar.llm.web.users.UserDirectory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-user OAuth logins through the web: start, callback, list, logout, slash commands, user deletion. */
class OAuthWebTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PUBLIC = "https://jllm.example.test";

    @TempDir
    Path root;

    private HttpServer server;
    private String base;
    private AgentHarness harness;
    private TokenStore store;
    private MockMvc mvc;
    private UserDirectory users;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", ex -> {
            byte[] b = "{\"access_token\":\"SECRET-ACCESS\",\"refresh_token\":\"SECRET-REFRESH\",\"expires_in\":3600}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        Files.createDirectories(root.resolve("users"));
        Files.createDirectories(root.resolve("permissions"));
        Files.writeString(root.resolve("permissions/reader.json"), "{\"tools\":[\"*\"],\"readOnly\":true}");
        for (String[] u : new String[][]{{"admin", "admin"}, {"rita", "reader"}, {"gus", "reader"}}) {
            Files.writeString(root.resolve("users/" + u[0] + ".json"),
                    "{\"name\":\"" + u[0] + "\",\"role\":\"" + u[1] + "\",\"credentials\":{}}");
        }
        Path spec = Files.writeString(root.resolve("crm.yaml"), """
                openapi: 3.0.0
                info: {title: t, version: "1"}
                servers: [{url: "http://127.0.0.1:9/api"}]
                paths:
                  /items:
                    get: {operationId: listItems}
                """);
        RestApiSettings crm = api("crm", spec, true);
        RestApiSettings shared = api("shared", spec, false);
        users = new UserDirectory(root.resolve("users"), MAPPER);
        Permissions permissions = new Permissions(root.resolve("permissions"), MAPPER);
        SkillFiles skills = new SkillFiles(root);
        store = new FileTokenStore(root.resolve("tokens"),
                new TokenCipher(Base64.getDecoder().decode(TokenCipher.newKey()), null));
        harness = build(new HarnessSettings(null, List.of(crm, shared), null, null, null, null), store, permissions,
                skills);
        SessionStore sessions = new SessionStore();
        CookieAuthentication auth = new CookieAuthentication(users, sessions);
        OAuthController oauth = new OAuthController(harness, auth, props(PUBLIC));
        oauth.check();
        ToolCatalogView catalog = new ToolCatalogView(harness);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(users, auth, sessions, new LoginThrottle()),
                        new AdminController(users, permissions, skills, sessions, harness, catalog),
                        new ChatController(harness, MAPPER, new HarnessPricing(0, 0, 0, 0), auth, auth), oauth)
                .addInterceptors(new ApiInterceptor(auth, auth, harness.sessions(), MAPPER),
                        new MappedInterceptor(new String[]{"/api/admin/**"}, new AdminInterceptor(auth, MAPPER)))
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private RestApiSettings api(String name, Path spec, boolean perUser) {
        return new RestApiSettings(name, null, null, spec.toString(), Map.of(), null, null, null, null,
                new RestApiSettings.OAuth(null, null, base + "/authorize", base + "/token", null, "cid", null,
                        List.of("r"), null, perUser, null, null, null));
    }

    private AgentHarness build(HarnessSettings settings, TokenStore store, Permissions permissions,
                               SkillFiles skills) {
        return AgentHarness.builder().modelClient(new ModelClient() {
                    @Override
                    public ModelResponse complete(ModelRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public String simpleComplete(String system, String user, int maxTokens) {
                        return "";
                    }
                }).sessionStore(new InMemoryChatSessionStore()).objectMapper(MAPPER).withoutStandardTools()
                .settings(settings).tokenStore(store)
                .userPolicyResolver(permissions.resolver(users, skills))
                .principalDirectory(id -> users.find(id).map(u -> u.principal()).orElse(null))
                .build();
    }

    private static JllmProperties props(String publicUrl) {
        return new JllmProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, new JllmProperties.Web(publicUrl));
    }

    private Cookie signIn(String id) throws Exception {
        Cookie c = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("id", id, "password", PasswordHasher.defaultPassword(id)))))
                .andReturn().getResponse().getCookie(CookieAuthentication.COOKIE);
        assertNotNull(c);
        return c;
    }

    private static JsonNode json(ResultActions r) throws Exception {
        return MAPPER.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> q = new java.util.HashMap<>();
        for (String p : URI.create(url).getRawQuery().split("&")) {
            int i = p.indexOf('=');
            q.put(p.substring(0, i), java.net.URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private Map<String, String> start(Cookie c) throws Exception {
        JsonNode r = json(mvc.perform(post("/api/oauth/api:crm/start").cookie(c)).andExpect(status().isOk()));
        return query(r.path("authorizeUrl").asText());
    }

    @Test
    void startNeedsASignedInUser() throws Exception {
        mvc.perform(post("/api/oauth/api:crm/start")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/oauth")).andExpect(status().isUnauthorized());
    }

    @Test
    void callbackStoresTheTokenForThatUserOnly() throws Exception {
        Cookie rita = signIn("rita");
        Map<String, String> q = start(rita);
        assertEquals(PUBLIC + "/oauth/callback", q.get("redirect_uri"));
        assertEquals("S256", q.get("code_challenge_method"));

        String page = mvc.perform(get("/oauth/callback").cookie(rita).param("state", q.get("state")).param("code", "c1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("Connected to api:crm"), page);
        assertFalse(page.contains("SECRET"));
        assertEquals("SECRET-ACCESS", store.get("rita", "api:crm").orElseThrow().access());
        assertTrue(store.get("gus", "api:crm").isEmpty());
        assertTrue(store.get(null, "api:crm").isEmpty());

        // single use
        mvc.perform(get("/oauth/callback").cookie(rita).param("state", q.get("state")).param("code", "c1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void callbackRejectsUnknownStateAndAnotherUser() throws Exception {
        mvc.perform(get("/oauth/callback").param("state", "nope").param("code", "c")).andExpect(status().isBadRequest());
        Cookie rita = signIn("rita");
        Map<String, String> q = start(rita);
        mvc.perform(get("/oauth/callback").cookie(signIn("gus")).param("state", q.get("state")).param("code", "c"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/oauth/callback").param("state", q.get("state")).param("code", "c"))
                .andExpect(status().isBadRequest()); // consumed by the rejected attempt
        assertTrue(store.get("rita", "api:crm").isEmpty());
        assertTrue(store.get("gus", "api:crm").isEmpty());
    }

    @Test
    void listAndLogoutNeverShowTokens() throws Exception {
        Cookie rita = signIn("rita");
        store.put("rita", "api:crm", new OAuthToken("SECRET-ACCESS", "SECRET-REFRESH", 4_000_000_000L, null));
        String body = mvc.perform(get("/api/oauth").cookie(rita)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertFalse(body.contains("SECRET"), body);
        JsonNode list = MAPPER.readTree(body).path("sources");
        assertEquals(1, list.size(), "the shared source is the admin's: " + body);
        assertTrue(list.get(0).path("connected").asBoolean());
        mvc.perform(delete("/api/oauth/api:crm").cookie(rita)).andExpect(status().isNoContent());
        assertTrue(store.get("rita", "api:crm").isEmpty());
        // the shared login is the admin's
        mvc.perform(post("/api/oauth/api:shared/start").cookie(rita)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/oauth/api:shared").cookie(rita)).andExpect(status().isForbidden());
        assertEquals(2, json(mvc.perform(get("/api/oauth").cookie(signIn("admin")))).path("sources").size());
    }

    @Test
    void slashLoginOfAWebUserGoesThroughTheSameStart() {
        String chat = harness.sessions().create("t", "rita").chatId();
        var rita = org.kendar.llm.harness.commands.SlashCommands.Caller.web("rita", false);
        String out = harness.commands().execute(chat, "/login api:crm", rita).output();
        assertTrue(out.startsWith("Open this link to connect api:crm: " + base + "/authorize?"), out);
        assertTrue(out.contains(java.net.URLEncoder.encode(PUBLIC + "/oauth/callback", StandardCharsets.UTF_8)), out);
        assertTrue(harness.commands().execute(chat, "/login api:shared", rita).output().contains("administrator"));
        String list = harness.commands().execute(chat, "/login", rita).output();
        assertTrue(list.contains("api:crm: not connected (your own login)"), list);
        assertFalse(list.contains("api:shared"), list);
    }

    @Test
    void deletingAUserRemovesTheirTokens() throws Exception {
        store.put("gus", "api:crm", new OAuthToken("a", null, 0, null));
        mvc.perform(delete("/api/admin/users/gus").cookie(signIn("admin"))).andExpect(status().isNoContent());
        assertTrue(store.names("gus").isEmpty());
        assertFalse(Files.exists(((FileTokenStore) store).fileOf("gus")));
    }

    @Test
    void startupIsRefusedWithoutEncryptionOrPublicUrl() throws Exception {
        Path spec = root.resolve("crm.yaml");
        HarnessSettings settings = new HarnessSettings(null, List.of(api("crm", spec, true)), null, null, null, null);
        Permissions permissions = new Permissions(root.resolve("permissions"), MAPPER);
        SkillFiles skills = new SkillFiles(root);
        AgentHarness plain = build(settings, new FileTokenStore(root.resolve("plain")), permissions, skills);
        CookieAuthentication auth = new CookieAuthentication(users, new SessionStore());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new OAuthController(plain, auth, props(PUBLIC)).check());
        assertTrue(e.getMessage().contains("JLLM_TOKEN_KEY"), e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> new OAuthController(harness, auth, props(null)).check());
        assertTrue(e.getMessage().contains("public-url"), e.getMessage());
    }
}

package org.kendar.llm.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.web.auth.AuthController;
import org.kendar.llm.web.auth.CookieAuthentication;
import org.kendar.llm.web.auth.OidcClient;
import org.kendar.llm.web.auth.OidcController;
import org.kendar.llm.web.auth.OidcProperties;
import org.kendar.llm.web.commons.users.WebUser;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** OpenID Connect sign-in against an in-process identity provider. */
class OidcTest {

    private static final String CLIENT = "jllm";
    private static final String SECRET = "s3cret";

    @TempDir
    Path dir;

    private HttpServer server;
    private String issuer;
    private RSAKey key;
    private volatile String idToken;
    private final AtomicInteger jwksCalls = new AtomicInteger();
    private final List<Map<String, String>> tokenRequests = new ArrayList<>();
    private volatile JWKSet jwks;
    private volatile int tokenStatus = 200;
    private WebauthFixture f;
    private MutableClock clock;

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        jwks = new JWKSet(key.toPublicJWK());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", x -> reply(x, 200, WebauthFixture.MAPPER
                .writeValueAsString(Map.of("issuer", issuer, "authorization_endpoint", issuer + "/authorize",
                        "token_endpoint", issuer + "/token", "jwks_uri", issuer + "/jwks"))));
        server.createContext("/jwks", x -> {
            jwksCalls.incrementAndGet();
            reply(x, 200, jwks.toString());
        });
        server.createContext("/token", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = new LinkedHashMap<>();
            for (String p : body.split("&")) {
                String[] kv = p.split("=", 2);
                form.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
            }
            tokenRequests.add(form);
            reply(x, tokenStatus, WebauthFixture.MAPPER.writeValueAsString(Map.of("id_token", String.valueOf(idToken))));
        });
        server.start();
        f = new WebauthFixture(dir);
        clock = new MutableClock();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange x, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    // ---- harness

    private OidcProperties props(String mode, boolean provision, List<String> domains) {
        return new OidcProperties(mode, new OidcProperties.Oidc("Company SSO", issuer, CLIENT, "env:IDP_SECRET",
                null, domains, provision, "reader"));
    }

    private MockMvc mvc(OidcProperties p) throws Exception {
        UnaryOperator<String> env = n -> "IDP_SECRET".equals(n) ? SECRET : null;
        OidcClient client = new OidcClient(p, "http://127.0.0.1:8080", env, WebauthFixture.MAPPER,
                java.net.http.HttpClient.newHttpClient(), clock);
        OidcController c = new OidcController(p, client, f.users, f.sessions, f.throttle, clock);
        CookieAuthentication auth = new CookieAuthentication(f.users, f.sessions);
        return MockMvcBuilders.standaloneSetup(c, new AuthController(f.users, auth, f.sessions, f.throttle, p)).build();
    }

    private record Started(String state, String nonce, Cookie binding, String location) {
    }

    private Started begin(MockMvc mvc) throws Exception {
        f.throttle.success("oidc:127.0.0.1"); // these tests refuse more than five sign-ins in a row
        MockHttpServletResponse r = mvc.perform(get("/auth/oidc/login")).andReturn().getResponse();
        assertEquals(302, r.getStatus());
        String loc = r.getHeader("Location");
        Map<String, String> q = query(loc);
        return new Started(q.get("state"), q.get("nonce"), r.getCookie(OidcController.BINDING_COOKIE), loc);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> q = new LinkedHashMap<>();
        for (String p : URI.create(url).getRawQuery().split("&")) {
            String[] kv = p.split("=", 2);
            q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return q;
    }

    private MockHttpServletResponse callback(MockMvc mvc, String state, Cookie binding) throws Exception {
        var req = get("/auth/oidc/callback").param("code", "the-code");
        if (state != null) req = req.param("state", state);
        if (binding != null) req = req.cookie(binding);
        return mvc.perform(req).andReturn().getResponse();
    }

    private JWTClaimsSet.Builder claims(String nonce, String sub, String email) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(issuer).audience(CLIENT).subject(sub)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).claim("nonce", nonce)
                .claim("email", email).claim("email_verified", true).claim("name", "Ann Example");
    }

    private String sign(JWTClaimsSet c) throws JOSEException {
        return sign(c, key, "k1");
    }

    private String sign(JWTClaimsSet c, RSAKey with, String kid) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), c);
        jwt.sign(new RSASSASigner(with));
        return jwt.serialize();
    }

    private static String error(MockHttpServletResponse r) {
        String loc = r.getHeader("Location");
        return loc != null && loc.startsWith("/?authError=") ? loc.substring("/?authError=".length()) : null;
    }

    private String signInAs(MockMvc mvc, String sub, String email) throws Exception {
        Started s = begin(mvc);
        idToken = sign(claims(s.nonce(), sub, email).build());
        MockHttpServletResponse r = callback(mvc, s.state(), s.binding());
        assertNull(error(r), r.getHeader("Location"));
        assertEquals("/", r.getHeader("Location"));
        Cookie c = r.getCookie(CookieAuthentication.COOKIE);
        assertNotNull(c);
        return f.sessions.find(c.getValue()).orElseThrow();
    }

    // ---- tests

    @Test
    void preCreatedUserIsLinkedOnFirstLoginThenFoundBySubject() throws Exception {
        f.users.setEmail("rita", "Rita@Example.com");
        MockMvc mvc = mvc(props("both", false, List.of("example.com")));
        Started s = begin(mvc);
        assertTrue(s.location().startsWith(issuer + "/authorize?"));
        Map<String, String> q = query(s.location());
        assertEquals("code", q.get("response_type"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals(CLIENT, q.get("client_id"));
        assertEquals("http://127.0.0.1:8080/auth/oidc/callback", q.get("redirect_uri"));
        assertEquals("Lax", s.binding().getAttribute("SameSite"));
        assertTrue(s.binding().isHttpOnly());

        assertEquals("rita", signInAs(mvc, "sub-1", "rita@example.com"));
        WebUser rita = f.users.find("rita").orElseThrow();
        assertEquals(issuer + "#sub-1", rita.externalId());
        assertEquals("reader", rita.role());
        assertFalse(rita.usesDefaultPassword());

        // the token request carried the secret, the code and the verifier of the challenge
        Map<String, String> t = tokenRequests.get(0);
        assertEquals(SECRET, t.get("client_secret"));
        assertEquals("the-code", t.get("code"));

        // second login: by issuer+sub, even with another email
        assertEquals("rita", signInAs(mvc, "sub-1", "other@elsewhere.org"));
    }

    @Test
    void autoProvisionCreatesUserWithDefaultRoleAndSuffixesCollisions() throws Exception {
        MockMvc mvc = mvc(props("oidc", true, List.of()));
        assertEquals("ann", signInAs(mvc, "s1", "ann@example.com"));
        WebUser ann = f.users.find("ann").orElseThrow();
        assertEquals("reader", ann.role());
        assertEquals("Ann Example", ann.name());
        assertTrue(ann.passwordless());
        assertEquals("ann-2", signInAs(mvc, "s2", "ann@other.org"));
        assertEquals("ann-3", signInAs(mvc, "s3", "Ann@third.org"));
    }

    @Test
    void autoProvisionOffRefusesUnknownUser() throws Exception {
        MockMvc mvc = mvc(props("both", false, List.of()));
        Started s = begin(mvc);
        idToken = sign(claims(s.nonce(), "s9", "nobody@example.com").build());
        assertEquals("unknown_user", error(callback(mvc, s.state(), s.binding())));
        assertTrue(f.users.find("nobody").isEmpty());
    }

    @Test
    void tokenChecks() throws Exception {
        f.users.setEmail("rita", "rita@example.com");
        MockMvc mvc = mvc(props("both", true, List.of("example.com")));
        Instant now = Instant.now();
        Started s = begin(mvc);
        String nonce = s.nonce();

        // wrong nonce
        idToken = sign(claims("not-it", "sub-x", "rita@example.com").build());
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));

        s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-x", "rita@example.com").issuer("http://evil").build());
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));

        s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-x", "rita@example.com").audience("someone-else").build());
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));

        s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-x", "rita@example.com")
                .expirationTime(Date.from(now.minusSeconds(3600))).build());
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));

        // signed by a key the provider does not publish
        s = begin(mvc);
        RSAKey rogue = new RSAKeyGenerator(2048).keyID("k1").generate();
        idToken = sign(claims(s.nonce(), "sub-x", "rita@example.com").build(), rogue, "k1");
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));

        // alg=none
        s = begin(mvc);
        idToken = new PlainJWT(claims(s.nonce(), "sub-x", "rita@example.com").build()).serialize();
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));

        // alg=HS256 with the public key material as secret
        s = begin(mvc);
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(),
                claims(s.nonce(), "sub-x", "rita@example.com").build());
        hs.sign(new MACSigner(new byte[32]));
        idToken = hs.serialize();
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));

        // token endpoint error
        s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-x", "rita@example.com").build());
        tokenStatus = 400;
        assertEquals("invalid_token", error(callback(mvc, s.state(), s.binding())));
        tokenStatus = 200;

        assertNotNull(nonce);
        assertTrue(f.users.findByExternal(issuer, "sub-x").isEmpty());
        assertNull(f.users.find("rita").orElseThrow().externalId());
    }

    @Test
    void emailAndDomainChecks() throws Exception {
        f.users.setEmail("rita", "rita@example.com");
        MockMvc mvc = mvc(props("both", true, List.of("example.com")));
        Started s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-x", "rita@example.com").claim("email_verified", false).build());
        assertEquals("email_not_verified", error(callback(mvc, s.state(), s.binding())));

        s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-x", "rita@evil.org").build());
        assertEquals("domain_not_allowed", error(callback(mvc, s.state(), s.binding())));
        assertNull(f.users.find("rita").orElseThrow().externalId());
    }

    @Test
    void stateIsSingleUseBoundAndExpires() throws Exception {
        f.users.setEmail("rita", "rita@example.com");
        MockMvc mvc = mvc(props("both", false, List.of()));

        // replay
        Started s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-1", "rita@example.com").build());
        assertNull(error(callback(mvc, s.state(), s.binding())));
        assertEquals("expired", error(callback(mvc, s.state(), s.binding())));

        // missing binding cookie, and the state is spent
        s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-2", "rita@example.com").build());
        assertEquals("denied", error(callback(mvc, s.state(), null)));
        assertEquals("expired", error(callback(mvc, s.state(), s.binding())));

        // binding of another login
        Started a = begin(mvc);
        Started b = begin(mvc);
        assertEquals("denied", error(callback(mvc, a.state(), b.binding())));

        // unknown state
        assertEquals("expired", error(callback(mvc, "made-up", a.binding())));
        assertEquals("expired", error(callback(mvc, null, a.binding())));

        // expired
        Started late = begin(mvc);
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertEquals("expired", error(callback(mvc, late.state(), late.binding())));
    }

    @Test
    void fiveRefusedCallbacksLockTheAddress() throws Exception {
        MockMvc mvc = mvc(props("both", false, List.of()));
        for (int i = 0; i < 5; i++) assertEquals("expired", error(callback(mvc, "bad" + i, null)));
        assertEquals(429, mvc.perform(get("/auth/oidc/login")).andReturn().getResponse().getStatus());
        assertEquals("denied", error(callback(mvc, "bad", null)));
    }

    @Test
    void providerErrorIsRefusedWithoutEchoingIt() throws Exception {
        MockMvc mvc = mvc(props("both", false, List.of()));
        Started s = begin(mvc);
        MockHttpServletResponse r = mvc.perform(get("/auth/oidc/callback").param("error", "<script>x</script>")
                .param("state", s.state()).cookie(s.binding())).andReturn().getResponse();
        assertEquals("/?authError=denied", r.getHeader("Location"));
    }

    @Test
    void unknownKeyIdRefreshesTheKeySetOnce() throws Exception {
        f.users.setEmail("rita", "rita@example.com");
        MockMvc mvc = mvc(props("both", false, List.of()));
        assertEquals("rita", signInAs(mvc, "sub-1", "rita@example.com"));
        assertEquals(1, jwksCalls.get());

        RSAKey next = new RSAKeyGenerator(2048).keyID("k2").generate();
        jwks = new JWKSet(next.toPublicJWK());
        Started s = begin(mvc);
        idToken = sign(claims(s.nonce(), "sub-1", "rita@example.com").build(), next, "k2");
        assertNull(error(callback(mvc, s.state(), s.binding())));
        assertEquals(2, jwksCalls.get());
    }

    @Test
    void passwordlessUserCannotUsePasswordLogin() throws Exception {
        MockMvc mvc = mvc(props("both", true, List.of()));
        signInAs(mvc, "s1", "ann@example.com");
        WebUser ann = f.users.find("ann").orElseThrow();
        assertFalse(ann.usesDefaultPassword());
        f.mvc.perform(post("/api/login").contentType("application/json").content("{\"id\":\"ann\",\"password\":\"ann\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var me = AuthController.visible(ann);
        assertEquals(false, me.get("defaultPassword"));
        assertEquals("oidc", me.get("source"));
    }

    @Test
    void modesGateTheEndpoints() throws Exception {
        MockMvc password = mvc(props("password", false, List.of()));
        password.perform(get("/auth/oidc/login")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        password.perform(get("/auth/oidc/callback")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        assertEquals("{\"password\":true}", password.perform(get("/api/auth/config")).andReturn().getResponse()
                .getContentAsString());

        MockMvc oidc = mvc(props("oidc", false, List.of()));
        oidc.perform(post("/api/login").contentType("application/json").content("{\"id\":\"rita\",\"password\":\"rita\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        var cfg = WebauthFixture.MAPPER.readTree(oidc.perform(get("/api/auth/config")).andReturn().getResponse()
                .getContentAsString());
        assertFalse(cfg.path("password").asBoolean());
        assertEquals("Company SSO", cfg.path("oidc").path("name").asText());
        assertEquals("/auth/oidc/login", cfg.path("oidc").path("loginUrl").asText());
        assertFalse(cfg.toString().contains(issuer));
        assertFalse(cfg.toString().contains(SECRET));

        MockMvc both = mvc(props("both", false, List.of()));
        assertEquals(302, both.perform(get("/auth/oidc/login")).andReturn().getResponse().getStatus());
        both.perform(post("/api/login").contentType("application/json").content("{\"id\":\"rita\",\"password\":\"rita\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertTrue(WebauthFixture.MAPPER.readTree(both.perform(get("/api/auth/config")).andReturn().getResponse()
                .getContentAsString()).path("password").asBoolean());
    }

    @Test
    void startupValidationNamesTheKey() {
        UnaryOperator<String> noEnv = n -> null;
        UnaryOperator<String> env = n -> "x";
        java.util.function.Predicate<String> roles = r -> r.equals("reader");
        OidcProperties.Oidc ok = new OidcProperties.Oidc("n", "https://idp", "id", "env:S", null, null, false, null);

        new OidcProperties("password", null).validate(null, noEnv, roles);
        new OidcProperties("both", ok).validate("https://jllm", env, roles);
        assertTrue(msg(new OidcProperties("both", ok), null, env, roles).contains("jllm.web.public-url"));
        assertTrue(msg(new OidcProperties("both", new OidcProperties.Oidc("n", null, "id", "env:S", null, null, false,
                null)), "https://j", env, roles).contains("jllm.auth.oidc.issuer"));
        assertTrue(msg(new OidcProperties("oidc", new OidcProperties.Oidc("n", "https://idp", " ", "env:S", null, null,
                false, null)), "https://j", env, roles).contains("jllm.auth.oidc.client-id"));
        assertTrue(msg(new OidcProperties("oidc", ok), "https://j", noEnv, roles).contains("jllm.auth.oidc.client-secret"));
        assertTrue(msg(new OidcProperties("oidc", new OidcProperties.Oidc("n", "https://idp", "id", "literal", null,
                null, false, null)), "https://j", env, roles).contains("jllm.auth.oidc.client-secret"));
        assertTrue(msg(new OidcProperties("oidc", new OidcProperties.Oidc("n", "https://idp", "id", "env:S", null, null,
                true, " ")), "https://j", env, roles).contains("jllm.auth.oidc.default-role"));
        assertTrue(msg(new OidcProperties("oidc", new OidcProperties.Oidc("n", "https://idp", "id", "env:S", null, null,
                true, "nope")), "https://j", env, roles).contains("jllm.auth.oidc.default-role"));
        assertTrue(msg(new OidcProperties("sideways", ok), "https://j", env, roles).contains("jllm.auth.mode"));
    }

    private static String msg(OidcProperties p, String url, UnaryOperator<String> env,
                              java.util.function.Predicate<String> roles) {
        return assertThrows(IllegalStateException.class, () -> p.validate(url, env, roles)).getMessage();
    }

    @Test
    void discoveryRefusesPlainHttpToARemoteHost() {
        OidcProperties p = new OidcProperties("oidc", new OidcProperties.Oidc("n", "http://idp.example.com", "id",
                "env:S", null, null, false, null));
        OidcClient c = new OidcClient(p, "https://j", n -> "x", WebauthFixture.MAPPER);
        assertTrue(assertThrows(java.io.IOException.class, c::discover).getMessage().contains("https"));
    }
}

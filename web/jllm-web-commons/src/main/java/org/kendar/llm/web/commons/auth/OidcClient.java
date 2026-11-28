package org.kendar.llm.web.commons.auth;

import com.nimbusds.jose.JOSEException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.kendar.llm.harness.auth.SecureUrl;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * The relying party side of OpenID Connect for one identity provider: discovery, the authorization URL, the code
 * exchange and the verification of the ID token. Nothing from the provider is kept except its public keys.
 */
public final class OidcClient {

    /**
     * What the application needs from a verified ID token.
     *
     * @param groups the group claim, read only when the role mapping asks for it
     */
    public record Identity(String issuer, String subject, String email, boolean emailVerified, String name,
                           Groups groups) {
    }

    /** Most groups a token may carry; more makes the claim unusable rather than cut. */
    public static final int MAX_GROUPS = 200;
    /** Longest group name; a longer one makes the claim unusable. */
    public static final int MAX_GROUP_LENGTH = 256;

    /**
     * The group claim of a verified ID token.
     *
     * @param status    whether the claim could be used
     * @param values    the groups, in token order without duplicates; empty unless {@link Status#PRESENT}
     * @param claimKeys the top-level claim names of the token, for the log when the claim is missing
     */
    public record Groups(Status status, List<String> values, List<String> claimKeys) {

        public enum Status {
            /** Not asked for: the role mapping is off. */
            NOT_READ,
            PRESENT,
            /** No such claim in the token. */
            MISSING,
            /** Entra ID overage: {@code _claim_names}/{@code _claim_sources} instead of the groups. Never fetched. */
            OVERAGE,
            /** Over {@value #MAX_GROUPS} groups, or a name over {@value #MAX_GROUP_LENGTH} characters. */
            OVERSIZED,
            /** Neither a string array nor a string. */
            INVALID
        }

        public static final Groups NOT_READ = new Groups(Status.NOT_READ, List.of(), List.of());

        public Groups {
            values = values == null ? List.of() : List.copyOf(values);
            claimKeys = claimKeys == null ? List.of() : List.copyOf(claimKeys);
        }
    }

    /**
     * The groups at {@code path} in the claims: the claim of that exact name, else the path split at dots through
     * nested objects ({@code realm_access.roles}). A string array, or a string of space or comma separated groups.
     */
    public static Groups groups(Map<String, Object> claims, String path) {
        List<String> keys = new ArrayList<>(new TreeSet<>(claims.keySet()));
        Object v = claims.get(path);
        if (v == null && path.contains(".")) {
            Object at = claims;
            for (String part : path.split("\\.", -1)) {
                at = at instanceof Map<?, ?> m ? m.get(part) : null;
                if (at == null) break;
            }
            v = at;
        }
        if (v == null) {
            boolean overage = claims.containsKey("_claim_names") || claims.containsKey("_claim_sources");
            return new Groups(overage ? Groups.Status.OVERAGE : Groups.Status.MISSING, List.of(), keys);
        }
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            if (list.size() > MAX_GROUPS) return new Groups(Groups.Status.OVERSIZED, List.of(), keys);
            for (Object o : list) {
                if (!(o instanceof String s)) return new Groups(Groups.Status.INVALID, List.of(), keys);
                out.add(s);
            }
        } else if (v instanceof String s) {
            for (String g : s.split("[\\s,]+")) if (!g.isEmpty()) out.add(g);
        } else {
            return new Groups(Groups.Status.INVALID, List.of(), keys);
        }
        List<String> distinct = out.stream().map(String::strip).filter(g -> !g.isEmpty()).distinct().toList();
        if (distinct.size() > MAX_GROUPS || distinct.stream().anyMatch(g -> g.length() > MAX_GROUP_LENGTH)) {
            return new Groups(Groups.Status.OVERSIZED, List.of(), keys);
        }
        return new Groups(Groups.Status.PRESENT, distinct, keys);
    }

    private record Discovery(String authorization, String token, String jwksUri) {
    }

    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);
    private static final Duration SKEW = Duration.ofSeconds(60);

    private final OidcProperties.Oidc config;
    private final String redirectUri;
    private final String secret;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Clock clock;
    private volatile Discovery discovery;
    private volatile JWKSet keys;

    public OidcClient(OidcProperties props, String publicUrl, UnaryOperator<String> env, ObjectMapper mapper) {
        this(props, publicUrl, env, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    public OidcClient(OidcProperties props, String publicUrl, UnaryOperator<String> env, ObjectMapper mapper,
                      HttpClient http, Clock clock) {
        this.config = props.oidc();
        this.secret = props.oidcEnabled() ? props.secret(env) : null;
        this.redirectUri = publicUrl == null ? null : publicUrl.strip().replaceAll("/+$", "") + OidcApi.CALLBACK;
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
    }

    public String issuer() {
        return config.issuerNormalized();
    }

    /** Reads the metadata of the provider; startup calls it so a wrong issuer stops the server. */
    public void discover() throws IOException {
        discovery();
    }

    private Discovery discovery() throws IOException {
        Discovery d = discovery;
        if (d != null) return d;
        synchronized (this) {
            if (discovery != null) return discovery;
            String url = issuer() + "/.well-known/openid-configuration";
            requireSecure(url, "issuer");
            JsonNode n = mapper.readTree(getBody(url));
            if (!issuer().equals(text(n, "issuer").replaceAll("/+$", ""))) {
                throw new IOException("Discovery issuer does not match jllm.auth.oidc.issuer");
            }
            Discovery found = new Discovery(text(n, "authorization_endpoint"), text(n, "token_endpoint"),
                    text(n, "jwks_uri"));
            requireSecure(found.authorization(), "authorization_endpoint");
            requireSecure(found.token(), "token_endpoint");
            requireSecure(found.jwksUri(), "jwks_uri");
            discovery = found;
            return found;
        }
    }

    public String authorizationUrl(String state, String nonce, String codeChallenge) throws IOException {
        String base = discovery().authorization();
        return base + (base.contains("?") ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + enc(config.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(config.scopesOrDefault())
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(codeChallenge)
                + "&code_challenge_method=S256";
    }

    /**
     * Exchanges the code, verifies the ID token and returns who signed in.
     *
     * @param groupClaim the claim path of the groups; null = not read ({@link Groups#NOT_READ})
     */
    public Identity exchange(String code, String verifier, String nonce, String groupClaim) throws OidcException {
        String idToken;
        try {
            String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri)
                    + "&client_id=" + enc(config.clientId()) + "&client_secret=" + enc(secret)
                    + "&code_verifier=" + enc(verifier);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(discovery().token()))
                            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new OidcException("invalid_token", "Token endpoint answered " + r.statusCode());
            }
            idToken = mapper.readTree(r.body()).path("id_token").asText("");
        } catch (IOException e) {
            throw new OidcException("invalid_token", "Token exchange failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcException("invalid_token", "Interrupted");
        }
        if (idToken.isBlank()) throw new OidcException("invalid_token", "No id_token in the token response");
        return verify(idToken, nonce, groupClaim);
    }

    Identity verify(String idToken, String nonce, String groupClaim) throws OidcException {
        try {
            SignedJWT jwt = SignedJWT.parse(idToken);
            JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
            if (!ALGORITHMS.contains(alg)) throw new OidcException("invalid_token", "Algorithm not allowed: " + alg);
            JWSVerifier verifier = verifier(alg, jwt.getHeader().getKeyID());
            if (!jwt.verify(verifier)) throw new OidcException("invalid_token", "Bad signature");
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!issuer().equals(c.getIssuer() == null ? null : c.getIssuer().replaceAll("/+$", ""))) {
                throw new OidcException("invalid_token", "Wrong issuer");
            }
            if (c.getAudience() == null || !c.getAudience().contains(config.clientId())) {
                throw new OidcException("invalid_token", "Wrong audience");
            }
            Instant now = clock.instant();
            Date exp = c.getExpirationTime();
            if (exp == null || exp.toInstant().plus(SKEW).isBefore(now)) {
                throw new OidcException("invalid_token", "Expired token");
            }
            Date iat = c.getIssueTime();
            if (iat == null || iat.toInstant().minus(SKEW).isAfter(now)) {
                throw new OidcException("invalid_token", "Bad issue time");
            }
            String n = c.getStringClaim("nonce");
            if (n == null || !MessageDigest.isEqual(n.getBytes(StandardCharsets.UTF_8),
                    nonce.getBytes(StandardCharsets.UTF_8))) {
                throw new OidcException("invalid_token", "Wrong nonce");
            }
            String sub = c.getSubject();
            if (sub == null || sub.isBlank()) throw new OidcException("invalid_token", "No subject");
            Object verified = c.getClaim("email_verified");
            boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(verified);
            Groups groups = groupClaim == null ? Groups.NOT_READ : groups(c.getClaims(), groupClaim);
            return new Identity(issuer(), sub, c.getStringClaim("email"), emailVerified, c.getStringClaim("name"),
                    groups);
        } catch (OidcException e) {
            throw e;
        } catch (Exception e) { // boundary: any unreadable or unverifiable token is an invalid token
            throw new OidcException("invalid_token", "Unreadable token: " + e.getClass().getSimpleName());
        }
    }

    /** The verifier for {@code kid}; the key set is fetched once and again, a single time, for an unknown key id. */
    private JWSVerifier verifier(JWSAlgorithm alg, String kid) throws OidcException, IOException {
        JWK key = find(kid, false);
        if (key == null) key = find(kid, true);
        if (key == null) throw new OidcException("invalid_token", "Unknown key id");
        try {
            if (JWSAlgorithm.RS256.equals(alg) && key instanceof RSAKey rsa) return new RSASSAVerifier(rsa);
            if (JWSAlgorithm.ES256.equals(alg) && key instanceof ECKey ec) return new ECDSAVerifier(ec);
        } catch (JOSEException e) {
            throw new OidcException("invalid_token", "Unusable key");
        }
        throw new OidcException("invalid_token", "Key does not fit the algorithm");
    }

    private JWK find(String kid, boolean refresh) throws IOException {
        JWKSet set;
        synchronized (this) {
            if (refresh || keys == null) {
                try {
                    keys = JWKSet.parse(getBody(discovery().jwksUri()));
                } catch (java.text.ParseException e) {
                    throw new IOException("Unreadable JWKS");
                }
            }
            set = keys;
        }
        if (kid != null) return set.getKeyByKeyId(kid);
        List<JWK> all = set.getKeys();
        return all.size() == 1 ? all.get(0) : null;
    }

    private String getBody(String url) throws IOException {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IOException(url + " answered " + r.statusCode());
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private static String text(JsonNode n, String field) throws IOException {
        String v = n.path(field).asText("");
        if (v.isBlank()) throw new IOException("Discovery has no " + field);
        return v;
    }

    /** HTTPS only; plain http is accepted for a loopback host, which tests use ({@link SecureUrl}). */
    static void requireSecure(String url, String what) throws IOException {
        try {
            SecureUrl.require(url, what);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

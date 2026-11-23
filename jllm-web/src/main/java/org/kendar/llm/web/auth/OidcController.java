package org.kendar.llm.web.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in through an OpenID Connect identity provider (authorization code flow with PKCE), ending in the same session
 * cookie as the password login:
 * <ul>
 *   <li>{@code GET /api/auth/config}: what the sign-in page offers; open;</li>
 *   <li>{@code GET /auth/oidc/login}: redirects to the provider;</li>
 *   <li>{@code GET /auth/oidc/callback}: where the provider sends the browser back.</li>
 * </ul>
 * The state is single-use, expires after 10 minutes and is bound to the browser by a cookie. A failure redirects to
 * {@code /?authError=<code>} with a code of {@link OidcException#CODES}; nothing the provider said is repeated.
 */
@RestController
public class OidcController {

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);
    public static final String LOGIN = "/auth/oidc/login";
    public static final String CALLBACK = "/auth/oidc/callback";
    public static final String BINDING_COOKIE = "jllm_oidc";
    static final Duration TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int MAX_PENDING = 10_000;

    private record Pending(String verifier, String nonce, String binding, Instant expires) {
    }

    private final OidcProperties props;
    private final OidcClient client;
    private final UserRepository users;
    private final LoginSessionRepository sessions;
    private final LoginThrottle throttle;
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public OidcController(OidcProperties props, OidcClient client, UserRepository users,
                          LoginSessionRepository sessions, LoginThrottle throttle) {
        this(props, client, users, sessions, throttle, Clock.systemUTC());
    }

    public OidcController(OidcProperties props, OidcClient client, UserRepository users,
                          LoginSessionRepository sessions, LoginThrottle throttle, Clock clock) {
        this.props = props;
        this.client = client;
        this.users = users;
        this.sessions = sessions;
        this.throttle = throttle;
        this.clock = clock;
    }

    @GetMapping("/api/auth/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("password", props.passwordEnabled());
        if (props.oidcEnabled()) {
            out.put("oidc", Map.of("name", props.oidc().nameOrDefault(), "loginUrl", LOGIN));
        }
        return out;
    }

    @GetMapping(LOGIN)
    public ResponseEntity<Void> login(HttpServletRequest request) {
        if (!props.oidcEnabled()) return ResponseEntity.notFound().build();
        if (throttle.lockedFor(key(request)) > 0) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        purge();
        if (pending.size() >= MAX_PENDING) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        String state = random(24);
        String nonce = random(24);
        String verifier = random(48);
        String binding = random(24);
        String url;
        try {
            url = client.authorizationUrl(state, nonce, challenge(verifier));
        } catch (IOException e) {
            log.warn("OIDC discovery failed: {}", e.toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        pending.put(state, new Pending(verifier, nonce, binding, clock.instant().plus(TTL)));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url)
                .header(HttpHeaders.SET_COOKIE, bindingCookie(binding, TTL.toSeconds(), request).toString()).build();
    }

    @GetMapping(CALLBACK)
    public ResponseEntity<Void> callback(@RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error,
                                         HttpServletRequest request) {
        if (!props.oidcEnabled()) return ResponseEntity.notFound().build();
        String key = key(request);
        if (throttle.lockedFor(key) > 0) return failure("denied", request);
        purge();
        Pending p = state == null ? null : pending.remove(state); // single use, whatever follows
        try {
            if (p == null || p.expires().isBefore(clock.instant())) throw new OidcException("expired", "Unknown or old state");
            String bound = bindingOf(request);
            if (bound == null || !MessageDigest.isEqual(bound.getBytes(StandardCharsets.UTF_8),
                    p.binding().getBytes(StandardCharsets.UTF_8))) {
                throw new OidcException("denied", "State not bound to this browser");
            }
            if (error != null) throw new OidcException("denied", "The provider refused: " + error.length() + " chars");
            if (code == null || code.isBlank()) throw new OidcException("denied", "No code");
            User user = resolve(client.exchange(code, p.verifier(), p.nonce()));
            throttle.success(key);
            String token = sessions.create(user.id());
            return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/")
                    .header(HttpHeaders.SET_COOKIE, AuthController.cookie(token,
                            LoginSessionRepository.TTL.toSeconds(), request).toString())
                    .header(HttpHeaders.SET_COOKIE, bindingCookie("", 0, request).toString()).build();
        } catch (OidcException e) {
            throttle.failure(key);
            log.warn("OIDC sign-in refused ({}): {}", e.code(), e.getMessage());
            return failure(e.code(), request);
        }
    }

    // ---- user resolution

    private User resolve(OidcClient.Identity id) throws OidcException {
        var linked = users.findByExternal(id.issuer(), id.subject());
        if (linked.isPresent()) return linked.get();
        String email = id.email();
        if (email == null || email.isBlank()) throw new OidcException("unknown_user", "No linked user and no email");
        if (!id.emailVerified()) throw new OidcException("email_not_verified", "Email not verified");
        List<String> allowed = props.oidc().domains();
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (!allowed.isEmpty() && !allowed.contains(domain)) {
            throw new OidcException("domain_not_allowed", "Domain " + domain);
        }
        String externalKey = UserRepository.externalKey(id.issuer(), id.subject());
        List<User> byEmail = users.findByEmail(email);
        if (!byEmail.isEmpty()) {
            if (byEmail.size() == 1 && !byEmail.get(0).external()) {
                return users.setExternalId(byEmail.get(0).id(), externalKey);
            }
            throw new OidcException("unknown_user", "Email matches no single unlinked user");
        }
        if (props.oidc().provisioning()) return provision(id, email, externalKey);
        throw new OidcException("unknown_user", "No user for the account");
    }

    private User provision(OidcClient.Identity id, String email, String externalKey) throws OidcException {
        String base = sanitize(email.substring(0, Math.max(0, email.lastIndexOf('@'))));
        String name = id.name() == null || id.name().isBlank() ? null : id.name().strip();
        for (int n = 1; n < 1000; n++) {
            String suffix = n == 1 ? "" : "-" + n;
            String candidate = base.substring(0, Math.min(base.length(), 64 - suffix.length())) + suffix;
            if (users.exists(candidate)) continue;
            try {
                return users.createExternal(candidate, name == null ? candidate : name, props.oidc().defaultRole(),
                        email, externalKey);
            } catch (IllegalStateException taken) {
                // created meanwhile: try the next id
            }
        }
        throw new OidcException("unknown_user", "No free id for " + base);
    }

    /** Lowercase; anything outside {@code [a-z0-9_-]} becomes {@code -}; at most 64 characters; never empty. */
    static String sanitize(String local) {
        String s = local.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "user" : s;
    }

    // ---- helpers

    /** Failed callbacks per address: five lock the address, for the login and the callback alike. */
    private static String key(HttpServletRequest request) {
        return "oidc:" + request.getRemoteAddr();
    }

    private void purge() {
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.expires().isBefore(now));
    }

    private ResponseEntity<Void> failure(String code, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/?authError=" + code)
                .header(HttpHeaders.SET_COOKIE, bindingCookie("", 0, request).toString()).build();
    }

    private static String bindingOf(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (BINDING_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    /** Lax, not Strict: the callback is a cross-site top-level navigation and must carry it. */
    private static ResponseCookie bindingCookie(String value, long maxAge, HttpServletRequest request) {
        return ResponseCookie.from(BINDING_COOKIE, value).path("/auth/oidc").sameSite("Lax").httpOnly(true)
                .secure(request.isSecure()).maxAge(maxAge).build();
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

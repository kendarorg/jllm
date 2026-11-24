package org.kendar.llm.web.commons.auth;

import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.commons.CookieOut;
import org.kendar.llm.web.commons.Cookies;
import org.kendar.llm.web.commons.Reply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class OidcApi {

    private static final Logger log = LoggerFactory.getLogger(OidcApi.class);
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

    public OidcApi(OidcProperties props, OidcClient client, UserRepository users,
                   LoginSessionRepository sessions, LoginThrottle throttle) {
        this(props, client, users, sessions, throttle, Clock.systemUTC());
    }

    public OidcApi(OidcProperties props, OidcClient client, UserRepository users,
                   LoginSessionRepository sessions, LoginThrottle throttle, Clock clock) {
        this.props = props;
        this.client = client;
        this.users = users;
        this.sessions = sessions;
        this.throttle = throttle;
        this.clock = clock;
    }

    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("password", props.passwordEnabled());
        if (props.oidcEnabled()) {
            out.put("oidc", Map.of("name", props.oidc().nameOrDefault(), "loginUrl", LOGIN));
        }
        return out;
    }

    /**
     * Redirects (302) to the provider and sets the cookie that binds the state to this browser.
     *
     * @param remoteAddress the caller's address, for the lock
     * @param secure        whether the request came over HTTPS
     */
    public Reply<Void> login(String remoteAddress, boolean secure) {
        if (!props.oidcEnabled()) return Reply.empty(404);
        if (throttle.lockedFor(key(remoteAddress)) > 0) return Reply.empty(429);
        purge();
        if (pending.size() >= MAX_PENDING) return Reply.empty(429);
        String state = random(24);
        String nonce = random(24);
        String verifier = random(48);
        String binding = random(24);
        String url;
        try {
            url = client.authorizationUrl(state, nonce, challenge(verifier));
        } catch (IOException e) {
            log.warn("OIDC discovery failed: {}", e.toString());
            return Reply.empty(502);
        }
        pending.put(state, new Pending(verifier, nonce, binding, clock.instant().plus(TTL)));
        return Reply.empty(302).header("Location", url).cookie(bindingCookie(binding, TTL.toSeconds(), secure));
    }

    /**
     * Where the provider sends the browser back: a redirect to {@code /} with the session cookie, or to
     * {@code /?authError=<code>}.
     *
     * @param cookies the request's cookies, holding the binding of the state
     */
    public Reply<Void> callback(String code, String state, String error, Cookies cookies, String remoteAddress,
                                boolean secure) {
        if (!props.oidcEnabled()) return Reply.empty(404);
        String key = key(remoteAddress);
        if (throttle.lockedFor(key) > 0) return failure("denied", secure);
        purge();
        Pending p = state == null ? null : pending.remove(state); // single use, whatever follows
        try {
            if (p == null || p.expires().isBefore(clock.instant())) throw new OidcException("expired", "Unknown or old state");
            String bound = bindingOf(cookies);
            if (bound == null || !MessageDigest.isEqual(bound.getBytes(StandardCharsets.UTF_8),
                    p.binding().getBytes(StandardCharsets.UTF_8))) {
                throw new OidcException("denied", "State not bound to this browser");
            }
            if (error != null) throw new OidcException("denied", "The provider refused: " + error.length() + " chars");
            if (code == null || code.isBlank()) throw new OidcException("denied", "No code");
            User user = resolve(client.exchange(code, p.verifier(), p.nonce()));
            throttle.success(key);
            String token = sessions.create(user.id());
            return Reply.empty(302).header("Location", "/")
                    .cookie(AuthApi.sessionCookie(token, LoginSessionRepository.TTL.toSeconds(), secure))
                    .cookie(bindingCookie("", 0, secure));
        } catch (OidcException e) {
            throttle.failure(key);
            log.warn("OIDC sign-in refused ({}): {}", e.code(), e.getMessage());
            return failure(e.code(), secure);
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
    private static String key(String remoteAddress) {
        return "oidc:" + remoteAddress;
    }

    private void purge() {
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.expires().isBefore(now));
    }

    private Reply<Void> failure(String code, boolean secure) {
        return Reply.empty(302).header("Location", "/?authError=" + code).cookie(bindingCookie("", 0, secure));
    }

    private static String bindingOf(Cookies cookies) {
        String v = cookies.get(BINDING_COOKIE);
        return v == null || v.isBlank() ? null : v;
    }

    /** Lax, not Strict: the callback is a cross-site top-level navigation and must carry it. */
    private static CookieOut bindingCookie(String value, long maxAge, boolean secure) {
        return new CookieOut(BINDING_COOKIE, value, "/auth/oidc", maxAge, true, secure, "Lax");
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

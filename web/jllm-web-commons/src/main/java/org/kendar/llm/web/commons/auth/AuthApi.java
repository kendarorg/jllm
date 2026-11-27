package org.kendar.llm.web.commons.auth;

import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.commons.Api;
import org.kendar.llm.web.commons.ApiException;
import org.kendar.llm.web.commons.CookieOut;
import org.kendar.llm.web.commons.Cookies;
import org.kendar.llm.web.commons.Reply;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Sign-in with login and password; the session cookie names a {@link LoginSessionRepository} session. */
public class AuthApi {

    private final UserRepository users;
    private final CookieAuthentication authentication;
    private final LoginSessionRepository sessions;
    private final LoginThrottle throttle;
    private final OidcProperties auth;
    private final Duration sessionMaxAge;
    private final Clock clock;

    /**
     * @param sessionMaxAge the absolute lifetime of a session: it ends then whatever its use ({@link
     *                      LoginSessionRepository#TTL} only slides while it is used)
     */
    public AuthApi(UserRepository users, CookieAuthentication authentication, LoginSessionRepository sessions,
                   LoginThrottle throttle, OidcProperties auth, Duration sessionMaxAge, Clock clock) {
        this.auth = auth;
        this.sessionMaxAge = sessionMaxAge;
        this.clock = clock;
        this.users = users;
        this.authentication = authentication;
        this.sessions = sessions;
        this.throttle = throttle;
    }

    public Map<String, Object> me(Cookies cookies) {
        return authentication.signedIn(cookies).map(AuthApi::visible)
                .orElseThrow(() -> new ApiException(401, "Sign in first"));
    }

    /**
     * Unknown id and wrong password answer the same 401, after the same work. Three failures for a login lock it for
     * 30 minutes, and an address that tries too often is locked too (429); the check and the count are one atomic
     * step ({@link LoginThrottle#begin}). The session ends {@code sessionMaxAge} after sign-in at the latest.
     *
     * @param remoteAddress the caller's address (see {@link org.kendar.llm.web.commons.ClientAddress}), for the lock
     * @param secure        whether the request came over HTTPS: the cookie is then {@code Secure}
     */
    public Reply<Map<String, Object>> login(Map<String, Object> body, String remoteAddress, boolean secure) {
        if (!auth.passwordEnabled()) throw new ApiException(404, "Password sign-in is off");
        String id = Api.text(body, "id");
        String password = Api.text(body, "password");
        String idKey = LoginThrottle.USER_PREFIX + (id == null ? "" : id.length() > 128 ? id.substring(0, 128) : id);
        String addressKey = "ip:" + remoteAddress;
        // the address first: a locked address must not use up the attempts of the login it names
        long locked = throttle.begin(addressKey);
        if (locked == 0) locked = throttle.begin(idKey);
        if (locked > 0) throw new ApiException(429, "Too many sign-in attempts; retry in " + locked + " s");
        Optional<User> user = users.find(id);
        boolean ok;
        if (user.isPresent()) {
            if (user.get().passwordless()) {
                PasswordHasher.burn(password);
                ok = false;
            } else {
                ok = PasswordHasher.verify(user.get().id(), password, user.get().password());
            }
        } else {
            PasswordHasher.burn(password);
            ok = false;
        }
        if (!ok) {
            throttle.failure(idKey);
            throttle.failure(addressKey);
            throw new ApiException(401, "Invalid credentials");
        }
        throttle.success(idKey);
        Instant notAfter = clock.instant().plus(sessionMaxAge);
        String token = sessions.create(user.get().id(), notAfter);
        return Reply.status(200, visible(user.get()))
                .cookie(sessionCookie(token, Math.min(LoginSessionRepository.TTL.toSeconds(), sessionMaxAge.toSeconds()),
                        secure));
    }

    /** Ends the session of the request (204) and clears the cookie. */
    public Reply<Void> logout(Cookies cookies, boolean secure) {
        CookieAuthentication.token(cookies).ifPresent(sessions::revoke);
        return Reply.empty(204).cookie(sessionCookie("", 0, secure));
    }

    static CookieOut sessionCookie(String value, long maxAge, boolean secure) {
        return new CookieOut(CookieAuthentication.COOKIE, value, "/", maxAge, true, secure, "Strict");
    }

    /** What the page may show: no credentials, no password hash. */
    public static Map<String, Object> visible(User u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.id());
        out.put("name", u.name() == null ? u.id() : u.name());
        out.put("role", String.valueOf(u.role()));
        out.put("defaultPassword", u.mustChangePassword()); // default or one-time password: the UI asks for a new one
        out.put("source", u.external() ? "oidc" : "password");
        out.put("email", u.email() == null ? "" : u.email());
        return out;
    }
}

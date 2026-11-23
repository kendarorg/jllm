package org.kendar.llm.web.commons.auth;

import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.commons.Api;
import org.kendar.llm.web.commons.ApiException;
import org.kendar.llm.web.commons.CookieOut;
import org.kendar.llm.web.commons.Cookies;
import org.kendar.llm.web.commons.Reply;

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

    public AuthApi(UserRepository users, CookieAuthentication authentication, LoginSessionRepository sessions,
                   LoginThrottle throttle, OidcProperties auth) {
        this.auth = auth;
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
     * Unknown id and wrong password answer the same 401, after the same work. Five failures for an id or an address
     * lock it for 30 seconds (429).
     *
     * @param remoteAddress the caller's address, for the lock
     * @param secure        whether the request came over HTTPS: the cookie is then {@code Secure}
     */
    public Reply<Map<String, Object>> login(Map<String, Object> body, String remoteAddress, boolean secure) {
        if (!auth.passwordEnabled()) throw new ApiException(404, "Password sign-in is off");
        String id = Api.text(body, "id");
        String password = Api.text(body, "password");
        String idKey = "id:" + id;
        String addressKey = "ip:" + remoteAddress;
        long locked = Math.max(throttle.lockedFor(idKey), throttle.lockedFor(addressKey));
        if (locked > 0) throw new ApiException(429, "Too many failed sign-ins; retry in " + locked + " s");
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
        throttle.success(addressKey);
        String token = sessions.create(user.get().id());
        return Reply.status(200, visible(user.get()))
                .cookie(sessionCookie(token, LoginSessionRepository.TTL.toSeconds(), secure));
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
        out.put("defaultPassword", u.usesDefaultPassword());
        out.put("source", u.external() ? "oidc" : "password");
        out.put("email", u.email() == null ? "" : u.email());
        return out;
    }
}

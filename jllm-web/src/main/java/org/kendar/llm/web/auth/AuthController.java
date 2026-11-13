package org.kendar.llm.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.web.Api;
import org.kendar.llm.web.users.UserDirectory;
import org.kendar.llm.web.users.WebUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Sign-in with login and password; the session cookie names a {@link SessionStore} session. */
@RestController
public class AuthController {

    private final UserDirectory users;
    private final CookieAuthentication authentication;
    private final SessionStore sessions;
    private final LoginThrottle throttle;

    public AuthController(UserDirectory users, CookieAuthentication authentication, SessionStore sessions,
                          LoginThrottle throttle) {
        this.users = users;
        this.authentication = authentication;
        this.sessions = sessions;
        this.throttle = throttle;
    }

    @GetMapping("/api/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        return authentication.signedIn(request).map(u -> ResponseEntity.ok(visible(u)))
                .orElse(Api.error(HttpStatus.UNAUTHORIZED, "Sign in first"));
    }

    /**
     * Unknown id and wrong password answer the same 401, after the same work. Five failures for an id or an address
     * lock it for 30 seconds (429).
     */
    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request) {
        String id = Api.text(body, "id");
        String password = Api.text(body, "password");
        String idKey = "id:" + id;
        String addressKey = "ip:" + request.getRemoteAddr();
        long locked = Math.max(throttle.lockedFor(idKey), throttle.lockedFor(addressKey));
        if (locked > 0) {
            return Api.error(HttpStatus.TOO_MANY_REQUESTS, "Too many failed sign-ins; retry in " + locked + " s");
        }
        Optional<WebUser> user = users.find(id);
        boolean ok;
        if (user.isPresent()) {
            ok = PasswordHasher.verify(user.get().id(), password, user.get().password());
        } else {
            PasswordHasher.burn(password);
            ok = false;
        }
        if (!ok) {
            throttle.failure(idKey);
            throttle.failure(addressKey);
            return Api.error(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        throttle.success(idKey);
        throttle.success(addressKey);
        String token = sessions.create(user.get().id());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(token, SessionStore.TTL.toSeconds(), request).toString())
                .body(visible(user.get()));
    }

    @PostMapping("/api/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        CookieAuthentication.token(request).ifPresent(sessions::revoke);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0, request).toString()).build();
    }

    static ResponseCookie cookie(String value, long maxAge, HttpServletRequest request) {
        return ResponseCookie.from(CookieAuthentication.COOKIE, value).path("/").sameSite("Strict").httpOnly(true)
                .secure(request.isSecure()).maxAge(maxAge).build();
    }

    /** What the page may show: no credentials, no password hash. */
    public static Map<String, Object> visible(WebUser u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.id());
        out.put("name", u.name() == null ? u.id() : u.name());
        out.put("role", String.valueOf(u.role()));
        out.put("defaultPassword", u.usesDefaultPassword());
        return out;
    }
}

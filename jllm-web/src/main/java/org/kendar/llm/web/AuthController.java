package org.kendar.llm.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The fake sign-in: pick a user, the cookie names them. No password, by design of the sample. */
@RestController
public class AuthController {

    private final UserDirectory users;
    private final UserInterceptor interceptor;

    public AuthController(UserDirectory users, UserInterceptor interceptor) {
        this.users = users;
        this.interceptor = interceptor;
    }

    @GetMapping("/api/users")
    public List<Map<String, Object>> list() {
        return users.all().stream().map(AuthController::visible).toList();
    }

    @GetMapping("/api/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        return interceptor.signedIn(request).map(u -> ResponseEntity.ok(visible(u)))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in first")));
    }

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String id = body == null ? null : body.get("id");
        return users.find(id).map(u -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookie(u.id(), 60L * 60 * 24 * 7).toString())
                        .body(visible(u)))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unknown user")));
    }

    @PostMapping("/api/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    private static ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(UserInterceptor.COOKIE, value).path("/").sameSite("Strict").httpOnly(true)
                .maxAge(maxAge).build();
    }

    /** What the page may show: no credentials. */
    private static Map<String, Object> visible(WebUser u) {
        return Map.of("id", u.id(), "name", u.name() == null ? u.id() : u.name(), "role", String.valueOf(u.role()));
    }
}

package org.kendar.llm.web.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.web.commons.auth.AuthApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sign-in with login and password: binds the request, calls the {@link AuthApi}. */
@RestController
public class AuthController {

    private final AuthApi auth;

    public AuthController(AuthApi auth) {
        this.auth = auth;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return auth.me(Requests.cookies(request));
    }

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request) {
        return Responses.entity(auth.login(body, request.getRemoteAddr(), request.isSecure()));
    }

    @PostMapping("/api/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        return Responses.entity(auth.logout(Requests.cookies(request), request.isSecure()));
    }
}

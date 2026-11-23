package org.kendar.llm.web.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.web.commons.auth.OidcApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sign-in through an OpenID Connect provider: binds the request, calls the {@link OidcApi}. */
@RestController
public class OidcController {

    private final OidcApi oidc;

    public OidcController(OidcApi oidc) {
        this.oidc = oidc;
    }

    @GetMapping("/api/auth/config")
    public Map<String, Object> config() {
        return oidc.config();
    }

    @GetMapping(OidcApi.LOGIN)
    public ResponseEntity<Void> login(HttpServletRequest request) {
        return Responses.entity(oidc.login(request.getRemoteAddr(), request.isSecure()));
    }

    @GetMapping(OidcApi.CALLBACK)
    public ResponseEntity<Void> callback(@RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error,
                                         HttpServletRequest request) {
        return Responses.entity(oidc.callback(code, state, error, Requests.cookies(request), request.getRemoteAddr(),
                request.isSecure()));
    }
}

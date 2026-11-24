package org.kendar.llm.web.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.OAuthApi;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** OAuth logins of REST APIs and MCP servers through the browser: binds the request, calls the {@link OAuthApi}. */
@RestController
public class OAuthController {

    private final OAuthApi oauth;
    private final WebAuthentication authentication;

    public OAuthController(OAuthApi oauth, WebAuthentication authentication) {
        this.oauth = oauth;
        this.authentication = authentication;
    }

    @PostMapping("/api/oauth/{source}/start")
    public Map<String, Object> start(@PathVariable String source,
                                     @RequestParam(value = "chatId", required = false) String chatId,
                                     @RequestParam(value = "shared", required = false) Boolean shared,
                                     @RequestAttribute(value = Principal.ATTR, required = false) Principal user) {
        return oauth.start(source, chatId, shared, user);
    }

    /** Not under the gate: the signed-in user is looked up here. */
    @GetMapping(value = OAuthApi.CALLBACK, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(value = "state", required = false) String state,
                                           @RequestParam(value = "code", required = false) String code,
                                           @RequestParam(value = "error", required = false) String error,
                                           HttpServletRequest request) {
        Principal user = authentication.current(Requests.cookies(request)).orElse(null);
        return Responses.entity(oauth.callback(state, code, error, user));
    }

    @GetMapping("/api/oauth")
    public Map<String, Object> list(@RequestAttribute(value = Principal.ATTR, required = false) Principal user) {
        return oauth.list(user);
    }

    @DeleteMapping("/api/oauth/{source}")
    public ResponseEntity<Void> logout(@PathVariable String source,
                                       @RequestParam(value = "shared", required = false) Boolean shared,
                                       @RequestAttribute(value = Principal.ATTR, required = false) Principal user) {
        oauth.logout(source, shared, user);
        return Responses.noContent();
    }
}

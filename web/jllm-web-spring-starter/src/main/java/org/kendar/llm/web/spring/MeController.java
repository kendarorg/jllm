package org.kendar.llm.web.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.auth.CookieAuthentication;
import org.kendar.llm.web.commons.me.MeApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The signed-in user's own settings: binds the request, calls the {@link MeApi}. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeApi me;

    public MeController(MeApi me) {
        this.me = me;
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@RequestBody(required = false) Map<String, Object> body,
                                         @RequestAttribute(value = Principal.ATTR, required = false) Principal principal,
                                         HttpServletRequest request) {
        me.password(body, principal, CookieAuthentication.token(Requests.cookies(request)).orElse(null));
        return Responses.noContent();
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(@RequestAttribute(value = Principal.ATTR, required = false) Principal principal) {
        return me.tools(principal);
    }

    @PutMapping("/tools")
    public ResponseEntity<Void> setTools(@RequestBody(required = false) Map<String, Object> body,
                                         @RequestAttribute(value = Principal.ATTR, required = false) Principal principal) {
        me.setTools(body, principal);
        return Responses.noContent();
    }

    @GetMapping("/skills")
    public Map<String, Object> skillList(@RequestAttribute(value = Principal.ATTR, required = false) Principal principal) {
        return me.skillList(principal);
    }

    @GetMapping("/skills/{name}")
    public Map<String, Object> skill(@PathVariable String name,
                                     @RequestAttribute(value = Principal.ATTR, required = false) Principal principal) {
        return me.skill(name, principal);
    }

    @PutMapping("/skills/enabled")
    public ResponseEntity<Void> setEnabledSkills(@RequestBody(required = false) Map<String, Object> body,
                                                 @RequestAttribute(value = Principal.ATTR, required = false)
                                                 Principal principal) {
        me.setEnabledSkills(body, principal);
        return Responses.noContent();
    }

    @PostMapping("/skills")
    public ResponseEntity<Map<String, Object>> createSkill(@RequestBody(required = false) Map<String, Object> body,
                                                           @RequestAttribute(value = Principal.ATTR, required = false)
                                                           Principal principal) {
        return Responses.entity(me.createSkill(body, principal));
    }

    @PutMapping("/skills/{name}")
    public Map<String, Object> updateSkill(@PathVariable String name,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           @RequestAttribute(value = Principal.ATTR, required = false) Principal principal) {
        return me.updateSkill(name, body, principal);
    }

    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String name,
                                            @RequestAttribute(value = Principal.ATTR, required = false) Principal principal) {
        me.deleteSkill(name, principal);
        return Responses.noContent();
    }
}

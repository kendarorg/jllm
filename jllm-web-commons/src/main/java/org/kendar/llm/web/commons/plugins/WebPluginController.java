package org.kendar.llm.web.commons.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.plugin.WebRequest;
import org.kendar.llm.harness.plugin.WebResponse;
import org.kendar.llm.web.commons.WebPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mounts web plugins under {@code /api/plugins/<id>/**}, a path reserved per plugin. The {@code /api/**} gate has
 * already authenticated the caller. {@code GET /api/plugins/<id>/events} is the user's live event stream.
 */
@RestController
public class WebPluginController {
    private final WebPluginHost host;

    public WebPluginController(WebPluginHost host) {
        this.host = host;
    }

    @RequestMapping("/api/plugins/{id}/**")
    public ResponseEntity<JsonNode> handle(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                                           HttpServletRequest http) {
        WebPrincipal user = (WebPrincipal) http.getAttribute(WebPrincipal.ATTR);
        if (user == null) return ResponseEntity.status(401).build();
        String prefix = http.getContextPath() + "/api/plugins/" + id;
        String path = http.getRequestURI().substring(prefix.length());
        Map<String, String> query = new HashMap<>();
        http.getParameterMap().forEach((k, v) -> query.put(k, v.length == 0 ? "" : v[0]));
        WebRequest req = new WebRequest(http.getMethod(), path, query, Map.of(), body, user.id(), user.admin(),
                user.admin() ? Set.of("admin") : Set.of());
        WebResponse r = host.dispatch(id, req);
        return ResponseEntity.status(r.status()).body(r.body());
    }

    @GetMapping("/api/plugins/{id}/events")
    public SseEmitter events(@PathVariable String id, HttpServletRequest http) {
        WebPrincipal user = (WebPrincipal) http.getAttribute(WebPrincipal.ATTR);
        var events = host.events(id);
        if (user == null || events == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        SseEmitter em = new SseEmitter(30 * 60 * 1000L);
        Runnable off = events.subscribe(user.id(), e -> {
            try {
                em.send(SseEmitter.event().name(e.type()).data(e.data().toString()));
            } catch (IOException | IllegalStateException ex) {
                em.complete();
            }
        });
        em.onCompletion(off);
        em.onTimeout(off);
        em.onError(t -> off.run());
        return em;
    }
}

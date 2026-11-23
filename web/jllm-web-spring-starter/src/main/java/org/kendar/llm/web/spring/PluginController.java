package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.plugins.PluginApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/** Mounts web plugins under {@code /api/plugins/<id>/**}: binds the request, calls the {@link PluginApi}. */
@RestController
public class PluginController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final PluginApi plugins;

    public PluginController(PluginApi plugins) {
        this.plugins = plugins;
    }

    @RequestMapping("/api/plugins/{id}/**")
    public ResponseEntity<JsonNode> handle(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                                           HttpServletRequest http) {
        String prefix = http.getContextPath() + "/api/plugins/" + id;
        String path = http.getRequestURI().substring(prefix.length());
        Map<String, String> query = new HashMap<>();
        http.getParameterMap().forEach((k, v) -> query.put(k, v.length == 0 ? "" : v[0]));
        return Responses.entity(plugins.handle(id, http.getMethod(), path, query, body, Requests.principal(http)));
    }

    @GetMapping("/api/plugins/{id}/events")
    public SseEmitter events(@PathVariable String id, HttpServletRequest http) {
        SseSink sink = new SseSink(SSE_TIMEOUT_MS);
        plugins.events(id, Requests.principal(http), sink);
        return sink.emitter();
    }
}

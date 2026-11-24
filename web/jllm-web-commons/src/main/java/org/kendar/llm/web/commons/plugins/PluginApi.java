package org.kendar.llm.web.commons.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.plugin.WebPluginHost;
import org.kendar.llm.harness.plugin.WebRequest;
import org.kendar.llm.harness.plugin.WebResponse;
import org.kendar.llm.web.commons.ApiException;
import org.kendar.llm.web.commons.EventSink;
import org.kendar.llm.web.commons.Reply;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Web plugins under {@code /api/plugins/<id>/**}, a path reserved per plugin. The {@code /api/**} gate has already
 * authenticated the caller. The user's live event stream of a plugin is {@code GET /api/plugins/<id>/events}.
 */
public class PluginApi {
    private final WebPluginHost host;

    public PluginApi(WebPluginHost host) {
        this.host = host;
    }

    /**
     * @param path  what follows {@code /api/plugins/<id>} in the request URI
     * @param query the first value of each query parameter
     */
    public Reply<JsonNode> handle(String id, String method, String path, Map<String, String> query, JsonNode body,
                                  Principal user) {
        if (user == null) throw new ApiException(401, "Sign in first");
        WebRequest req = new WebRequest(method, path, query, Map.of(), body, user.id(), user.admin(),
                user.admin() ? Set.of("admin") : Set.of());
        WebResponse r = host.dispatch(id, req);
        return Reply.status(r.status(), r.body());
    }

    /** Streams the user's events of the plugin to {@code sink}; the subscription ends with the stream. */
    public void events(String id, Principal user, EventSink sink) {
        var events = host.events(id);
        if (user == null || events == null) throw new ApiException(404, "Unknown plugin " + id);
        Runnable off = events.subscribe(user.id(), e -> {
            try {
                sink.send(null, e.type(), e.data().toString());
            } catch (IOException | IllegalStateException ex) {
                sink.complete();
            }
        });
        sink.onClose(off);
        sink.onTimeout(off);
    }
}

package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/** A framework-neutral request, already authenticated by the host. {@code path} is relative to the plugin's mount. */
public record WebRequest(String method, String path, Map<String, String> query, Map<String, String> pathParams,
                         JsonNode body, String user, boolean admin, Set<String> roles) {

    public WebRequest withPathParams(Map<String, String> p) {
        return new WebRequest(method, path, query, p, body, user, admin, roles);
    }

    public String param(String name) {
        return pathParams.get(name);
    }
}

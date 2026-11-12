package org.kendar.llm.harness.settings;

import java.util.List;
import java.util.Map;

/**
 * A REST API described by an OpenAPI spec. Only the operations matching {@code allowedPaths} become tools.
 *
 * @param baseUrl      overrides the first server of the spec
 * @param openApiUrl   spec location (http/https); alternative to {@code openApiFile}
 * @param enabled      default true
 * @param allowedPaths entries like {@code "GET /pets"}, {@code "/pets/{id}"} or {@code "/store/*"}; the method is
 *                     optional and {@code *} is a wildcard. Empty exposes nothing.
 */
public record RestApiSettings(String name, String baseUrl, String openApiUrl, String openApiFile,
                              Map<String, String> headers, String group, List<String> allowedPaths,
                              Boolean enabled) {

    public RestApiSettings {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
    }

    public boolean active() {
        return enabled == null || enabled;
    }

    public String effectiveGroup() {
        return group == null || group.isBlank() ? "Rest/" + name : group;
    }
}

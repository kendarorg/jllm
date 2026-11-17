package org.kendar.llm.harness.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A REST API described by an OpenAPI spec. Only the operations that pass {@code allowedPaths} and {@code denyPaths}
 * become tools.
 *
 * @param baseUrl      overrides the first server of the spec
 * @param openApiUrl   spec location (http/https); alternative to {@code openApiFile}
 * @param enabled      default true
 * @param allowedPaths entries like {@code "GET /pets"}, {@code "/pets/{id}"} or {@code "/store/*"}; the method is
 *                     optional and {@code *} is a wildcard. {@code null} (absent) allows all operations, an empty
 *                     list allows none, {@code ["*"]} all.
 * @param denyPaths    same syntax; matching operations are hidden. Empty or absent hides nothing, {@code ["*"]}
 *                     hides everything.
 * @param oauth        OAuth2 for the calls; null: none (static {@code headers} only)
 */
public record RestApiSettings(String name, String baseUrl, String openApiUrl, String openApiFile,
                              Map<String, String> headers, String group, List<String> allowedPaths,
                              Boolean enabled, List<String> denyPaths, OAuthSettings oauth) {

    public RestApiSettings {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        allowedPaths = allowedPaths == null ? null : List.copyOf(allowedPaths);
        denyPaths = denyPaths == null ? null : List.copyOf(denyPaths);
        if (oauth != null) oauth.validate("REST API '" + name + "'");
    }

    public boolean active() {
        return enabled == null || enabled;
    }

    public String effectiveGroup() {
        return group == null || group.isBlank() ? "Rest/" + name : group;
    }

    /** The token store and credential source name of this API: {@code api:<name>}. */
    public String source() {
        return "api:" + name;
    }
}

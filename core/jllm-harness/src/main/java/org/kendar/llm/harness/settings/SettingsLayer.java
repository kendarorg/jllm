package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One layer of the settings, as read from wherever it is kept.
 *
 * @param kind   what the layer is: the project layer is the one whose servers, hooks and OAuth blocks need trust and
 *               whose {@code tokenStore} is ignored
 * @param origin where it comes from, for messages (a file path, a database scope)
 * @param tree   the settings document; a non-object root is ignored with a warning
 */
public record SettingsLayer(Kind kind, String origin, JsonNode tree) {

    /** In order of precedence: a later layer wins. */
    public enum Kind {
        /** The user's own settings ({@code ~/.jllm/settings.json}). */
        GLOBAL,
        /** The project's settings ({@code <project>/.jllm/settings.json}): shared, untrusted. */
        PROJECT,
        /** Settings named explicitly by whoever starts the harness ({@code --settings}, {@code jllm.settings}). */
        EXPLICIT
    }
}

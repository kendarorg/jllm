package org.kendar.llm.js;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code plugins.js.settings}. */
public record JsSettings(long statementLimit, int maxTimeoutSeconds, int gracefulSeconds, boolean fileJail) {

    public static JsSettings defaults() {
        return new JsSettings(10_000_000L, 120, 10, true);
    }

    public static JsSettings from(JsonNode n) {
        JsSettings d = defaults();
        if (n == null || !n.isObject()) return d;
        return new JsSettings(n.path("statementLimit").asLong(d.statementLimit()),
                n.path("maxTimeoutSeconds").asInt(d.maxTimeoutSeconds()),
                n.path("gracefulSeconds").asInt(d.gracefulSeconds()),
                n.path("fileJail").asBoolean(d.fileJail()));
    }
}

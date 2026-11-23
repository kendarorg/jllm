package org.kendar.llm.js;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Test helpers: settings from key and JSON value pairs, sandboxes by engine. */
final class JsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsTest() {
    }

    /** {@code settings("engine", "\"constrained\"", "maxWallSeconds", "2")}: values are JSON. */
    static JsSettings settings(String... keyAndJsonValue) {
        ObjectNode n = MAPPER.createObjectNode();
        try {
            for (int i = 0; i < keyAndJsonValue.length; i += 2) {
                n.set(keyAndJsonValue[i], MAPPER.readTree(keyAndJsonValue[i + 1]));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return JsSettings.from(n);
    }

    static JsSettings settingsFor(String engine, String... keyAndJsonValue) {
        String[] kv = new String[keyAndJsonValue.length + 2];
        kv[0] = "engine";
        kv[1] = "\"" + engine + "\"";
        System.arraycopy(keyAndJsonValue, 0, kv, 2, keyAndJsonValue.length);
        return settings(kv);
    }

    static boolean isolateAvailable() {
        return GraalProbe.probe().isolate();
    }

    static JsSandbox box(String engine, String... keyAndJsonValue) {
        return JsSandbox.open(settingsFor(engine, keyAndJsonValue));
    }
}

package org.kendar.llm.web.commons;

import java.util.Map;

/** Reading request bodies. */
public final class Api {

    private Api() {
    }

    /** The string under {@code key}, or null when the body has none. */
    public static String text(Map<String, ?> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }
}

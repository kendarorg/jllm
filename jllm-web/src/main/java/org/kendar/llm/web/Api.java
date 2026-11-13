package org.kendar.llm.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Error replies in the shape of the common API: {@code {"error": "..."}}. */
public final class Api {

    private Api() {
    }

    public static <T> ResponseEntity<T> error(HttpStatus status, String message) {
        @SuppressWarnings("unchecked")
        T body = (T) Map.of("error", message);
        return ResponseEntity.status(status).body(body);
    }

    public static String text(Map<String, ?> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }
}

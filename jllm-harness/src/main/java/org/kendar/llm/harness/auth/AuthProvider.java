package org.kendar.llm.harness.auth;

import java.util.Map;
import java.util.function.Supplier;

/** Supplies the authentication headers of each outgoing request. */
public interface AuthProvider {

    /** Headers to set on the request (e.g. {@code Authorization}, {@code x-api-key}, extra beta headers). */
    Map<String, String> headers();

    /** The server answered 401: drop any cached token so the next {@link #headers()} fetches a fresh one. */
    default void onUnauthorized() {
    }

    /** A fixed key sent as {@code header: prefix + key}. A null or blank key sends no header. */
    static AuthProvider staticKey(String header, String prefix, String key) {
        Map<String, String> h = key == null || key.isBlank() ? Map.of() : Map.of(header, (prefix == null ? "" : prefix) + key);
        return () -> h;
    }

    /** A key obtained on every call (rotating or externally fetched keys). */
    static AuthProvider dynamicKey(String header, String prefix, Supplier<String> key) {
        return () -> {
            String k = key.get();
            return k == null || k.isBlank() ? Map.of() : Map.of(header, (prefix == null ? "" : prefix) + k);
        };
    }
}

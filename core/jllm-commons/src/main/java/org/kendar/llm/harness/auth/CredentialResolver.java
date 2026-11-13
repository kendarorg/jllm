package org.kendar.llm.harness.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Headers for one outgoing MCP or REST call, resolved per call (tokens expire and refresh).
 * Headers from the settings file are always sent; these are added on top.
 */
@FunctionalInterface
public interface CredentialResolver {

    /** Adds nothing: the single-user default. */
    CredentialResolver NONE = (user, source) -> Map.of();

    /**
     * @param user   the chat's principal; null when the chat has none
     * @param source "mcp:&lt;server&gt;" or "api:&lt;name&gt;"
     * @throws LoginRequiredException when the source needs a login the user has not done
     */
    Map<String, String> headers(HarnessPrincipal user, String source);

    /**
     * The server answered 401 to a call made with these headers: drop or refresh what is cached.
     *
     * @return true when the next {@link #headers} may give different credentials (the call is worth one retry)
     */
    default boolean onUnauthorized(HarnessPrincipal user, String source) {
        return false;
    }

    /**
     * Both resolvers; a header of {@code b} replaces the same header of {@code a}, whatever its case. A null resolver
     * is skipped.
     */
    static CredentialResolver chain(CredentialResolver a, CredentialResolver b) {
        if (a == null || a == NONE) return b == null ? NONE : b;
        if (b == null || b == NONE) return a;
        return new CredentialResolver() {
            @Override
            public Map<String, String> headers(HarnessPrincipal user, String source) {
                Map<String, String> out = new LinkedHashMap<>(a.headers(user, source));
                Map<String, String> over = b.headers(user, source);
                if (!over.isEmpty()) {
                    out.keySet().removeIf(k -> over.keySet().stream().anyMatch(o -> o.toLowerCase(Locale.ROOT)
                            .equals(k.toLowerCase(Locale.ROOT))));
                    out.putAll(over);
                }
                return out;
            }

            @Override
            public boolean onUnauthorized(HarnessPrincipal user, String source) {
                boolean x = a.onUnauthorized(user, source);
                return b.onUnauthorized(user, source) || x;
            }
        };
    }
}

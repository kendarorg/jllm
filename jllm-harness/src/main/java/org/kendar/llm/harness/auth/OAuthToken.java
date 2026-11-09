package org.kendar.llm.harness.auth;

import java.util.Map;

/**
 * @param access    access token
 * @param refresh   refresh token, or null
 * @param expiresAt epoch seconds the access token expires; 0 = unknown (never treated as expiring)
 * @param extra     other string fields of the token response (e.g. {@code id_token}, {@code scope}); never null
 */
public record OAuthToken(String access, String refresh, long expiresAt, Map<String, String> extra) {

    public OAuthToken {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** True when the token expires within {@code skewSeconds} of {@code nowEpochSeconds}. */
    public boolean expiresWithin(long nowEpochSeconds, long skewSeconds) {
        return expiresAt != 0 && nowEpochSeconds + skewSeconds >= expiresAt;
    }

    @Override
    public String toString() {
        return "OAuthToken[expiresAt=" + expiresAt + "]"; // never print secrets
    }
}

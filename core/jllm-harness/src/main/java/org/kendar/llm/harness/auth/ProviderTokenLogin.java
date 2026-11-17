package org.kendar.llm.harness.auth;

import java.util.Locale;
import java.util.Map;

/**
 * The per-user token of an LLM provider ({@code userAuth: token}), source {@code provider:<name>}: each user's own API
 * key kept in the {@link TokenStore} as an {@link OAuthToken} with no refresh token and no expiry. There is no
 * interactive login flow: the key is entered by the user (web settings, or {@code /login} in the CLI).
 */
public final class ProviderTokenLogin implements OAuthLogin {

    private final String name;
    private final String type;

    /** @param type the provider's effective type: {@code anthropic} sends {@code x-api-key}, others a Bearer token */
    public ProviderTokenLogin(String name, String type) {
        this.name = name;
        this.type = type == null ? "" : type.toLowerCase(Locale.ROOT);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public OAuthToken login(UserInteraction ui) {
        throw new OAuthException("unsupported", name + " takes an API key, not a login flow");
    }

    /** The header carrying a key of this provider type. */
    public static Map<String, String> header(String type, String key) {
        return "anthropic".equals(type) ? Map.of("x-api-key", key) : Map.of("Authorization", "Bearer " + key);
    }

    @Override
    public AuthProvider authProvider(TokenStore store, String principalId) {
        return new RefreshingAuthProvider(principalId, name, store, t -> t, t -> header(type, t.access()),
                () -> System.currentTimeMillis() / 1000);
    }
}

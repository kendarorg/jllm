package org.kendar.llm.harness.auth;

import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Serves a stored token, refreshing it ahead of expiry. The refresh runs under the {@link TokenStore} lock after
 * re-reading the store, so parallel agents and other processes refresh once, and a rotated refresh token is persisted
 * before the new access token is used.
 */
public final class RefreshingAuthProvider implements AuthProvider {

    private static final long SKEW_SECONDS = 60;

    private final String name;
    private final TokenStore store;
    private final Function<String, OAuthToken> refresher;
    private final Function<OAuthToken, Map<String, String>> headerBuilder;
    private final LongSupplier clock;
    private volatile boolean forceRefresh;

    /** @param refresher exchanges a refresh token for a new token; @param headerBuilder turns a token into headers */
    public RefreshingAuthProvider(String name, TokenStore store, Function<String, OAuthToken> refresher,
                                  Function<OAuthToken, Map<String, String>> headerBuilder, LongSupplier clock) {
        this.name = name;
        this.store = store;
        this.refresher = refresher;
        this.headerBuilder = headerBuilder;
        this.clock = clock;
    }

    public RefreshingAuthProvider(String name, TokenStore store, Function<String, OAuthToken> refresher) {
        this(name, store, refresher, t -> Map.of("authorization", "Bearer " + t.access()),
                () -> System.currentTimeMillis() / 1000);
    }

    @Override
    public Map<String, String> headers() {
        OAuthToken t = store.locked(() -> {
            OAuthToken token = store.get(name).orElseThrow(() -> new IllegalStateException(
                    "Not logged in to " + name + ": run /login " + name));
            boolean force = forceRefresh;
            if (!force && !token.expiresWithin(clock.getAsLong(), SKEW_SECONDS)) return token;
            if (token.refresh() == null) {
                if (token.expiresWithin(clock.getAsLong(), SKEW_SECONDS)) {
                    throw new IllegalStateException("The login to " + name + " expired: run /login " + name);
                }
                forceRefresh = false;
                return token;
            }
            try {
                OAuthToken fresh = refresher.apply(token.refresh());
                store.put(name, fresh); // persisted before it is used
                forceRefresh = false;
                return fresh;
            } catch (OAuthException e) {
                if (e.code().equals("invalid_grant")) {
                    throw new IllegalStateException("The login to " + name + " is no longer valid: run /login " + name, e);
                }
                throw e;
            }
        });
        return headerBuilder.apply(t);
    }

    @Override
    public void onUnauthorized() {
        forceRefresh = true;
    }
}

package org.kendar.llm.harness.auth;

import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Serves the stored token of one {@code (principal, name)}, refreshing it ahead of expiry.
 * <p>
 * Providers that rotate refresh tokens invalidate the old one at each refresh, so two refreshers of one entry would
 * leave one of them with a dead token. A refresh therefore runs inside {@link TokenStore#locked}, re-reads the entry
 * first (another refresher may have stored a fresh token already), stores the result with
 * {@link TokenStore#compareAndPut} before using it, and uses the stored token when the compare fails. With a store
 * whose {@link TokenStore#crossProcess()} is true this holds across processes, else inside one process.
 */
public final class RefreshingAuthProvider implements AuthProvider {

    private static final long SKEW_SECONDS = 60;

    private final String principalId;
    private final String name;
    private final TokenStore store;
    private final Function<OAuthToken, OAuthToken> refresher;
    private final Function<OAuthToken, Map<String, String>> headerBuilder;
    private final LongSupplier clock;
    private volatile boolean forceRefresh;

    /**
     * @param principalId   null: the single user or the shared login
     * @param refresher     exchanges the refresh token of the given token for a new token
     * @param headerBuilder turns a token into headers
     */
    public RefreshingAuthProvider(String principalId, String name, TokenStore store,
                                  Function<OAuthToken, OAuthToken> refresher,
                                  Function<OAuthToken, Map<String, String>> headerBuilder, LongSupplier clock) {
        this.principalId = principalId;
        this.name = name;
        this.store = store;
        this.refresher = refresher;
        this.headerBuilder = headerBuilder;
        this.clock = clock;
    }

    public RefreshingAuthProvider(String name, TokenStore store, Function<OAuthToken, OAuthToken> refresher) {
        this(null, name, store, refresher, t -> Map.of("authorization", "Bearer " + t.access()),
                () -> System.currentTimeMillis() / 1000);
    }

    /** @throws LoginRequiredException when there is no token, it expired without refresh token, or was revoked */
    @Override
    public Map<String, String> headers() {
        return headerBuilder.apply(token());
    }

    /** The current access token, refreshed when needed. */
    public OAuthToken token() {
        OAuthToken seen = store.get(principalId, name).orElseThrow(() -> new LoginRequiredException(name));
        if (!forceRefresh && !seen.expiresWithin(clock.getAsLong(), SKEW_SECONDS)) return seen;
        return store.locked(principalId, name, () -> {
            OAuthToken cur = store.get(principalId, name).orElseThrow(() -> new LoginRequiredException(name));
            boolean expiring = cur.expiresWithin(clock.getAsLong(), SKEW_SECONDS);
            if (!cur.equals(seen) && !expiring) { // another refresher was faster
                forceRefresh = false;
                return cur;
            }
            if (cur.refresh() == null) {
                if (expiring) throw new LoginRequiredException(name);
                forceRefresh = false;
                return cur;
            }
            OAuthToken fresh;
            try {
                fresh = refresher.apply(cur);
            } catch (OAuthException e) {
                if (e.code().equals("invalid_grant")) {
                    store.compareAndPut(principalId, name, cur, null); // revoked: drop it
                    throw new LoginRequiredException(name, e);
                }
                throw e;
            }
            forceRefresh = false;
            if (store.compareAndPut(principalId, name, cur, fresh)) return fresh; // persisted before it is used
            return store.get(principalId, name).orElseThrow(() -> new LoginRequiredException(name));
        });
    }

    @Override
    public void onUnauthorized() {
        forceRefresh = true;
    }
}

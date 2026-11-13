package org.kendar.llm.harness.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The OAuth headers of the REST APIs and MCP servers that declare OAuth, per call and per caller:
 * <pre>
 * headers(principal, "api:crm"):
 *   no oauth for this source            -> {}
 *   client_credentials                  -> cached service token (memory, renewed 60 s before expiry)
 *   perUser ? principal.id : null       -> key
 *   no stored token for (key, source)   -> LoginRequiredException
 *   else                                -> the token's header, refreshed as needed
 * </pre>
 * One {@link RefreshingAuthProvider} per {@code (key, source)}, in a bounded cache; {@link #forget} drops it (logout).
 * Joined after the embedder's resolver with {@link CredentialResolver#chain}, so its header wins.
 */
public final class OAuthCredentialResolver implements CredentialResolver {

    private static final int MAX_PROVIDERS = 1000;
    private static final long SKEW_SECONDS = 60;

    /**
     * One OAuth source.
     *
     * @param login             builds the refreshing provider of a key
     * @param perUser           every principal has their own token
     * @param clientCredentials fetches a service token; null when the source uses stored logins
     * @param header            the header of a service token
     */
    public record Source(String name, OAuthLogin login, boolean perUser, Supplier<OAuthToken> clientCredentials,
                         Function<OAuthToken, Map<String, String>> header) {
    }

    private record Key(String principal, String source) {
    }

    private final TokenStore store;
    private final Map<String, Source> sources;
    private final LongSupplier clock;
    private final Map<Key, AuthProvider> providers = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, AuthProvider> eldest) {
            return size() > MAX_PROVIDERS;
        }
    };
    private final Map<String, OAuthToken> serviceTokens = new java.util.concurrent.ConcurrentHashMap<>();

    public OAuthCredentialResolver(TokenStore store, Iterable<Source> sources) {
        this(store, sources, () -> System.currentTimeMillis() / 1000);
    }

    public OAuthCredentialResolver(TokenStore store, Iterable<Source> sources, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        Map<String, Source> m = new LinkedHashMap<>();
        for (Source s : sources) m.put(s.name(), s);
        this.sources = Map.copyOf(m);
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** Whether the source is handled here. */
    public boolean handles(String source) {
        return sources.containsKey(source);
    }

    /** Whether each user logs in to the source on their own (false: one shared login, or a service account). */
    public boolean perUser(String source) {
        Source s = sources.get(source);
        return s != null && s.perUser() && s.clientCredentials() == null;
    }

    public boolean serviceAccount(String source) {
        Source s = sources.get(source);
        return s != null && s.clientCredentials() != null;
    }

    /** The store key of a caller for a source: their id when the source is per user, else null. */
    public String keyOf(HarnessPrincipal user, String source) {
        return perUser(source) && user != null ? user.id() : null;
    }

    @Override
    public Map<String, String> headers(HarnessPrincipal user, String source) {
        Source s = sources.get(source);
        if (s == null) return Map.of();
        if (s.clientCredentials() != null) return s.header().apply(serviceToken(s));
        return provider(new Key(keyOf(user, source), source), s).headers();
    }

    @Override
    public boolean onUnauthorized(HarnessPrincipal user, String source) {
        Source s = sources.get(source);
        if (s == null) return false;
        if (s.clientCredentials() != null) {
            serviceTokens.remove(source);
            return true;
        }
        provider(new Key(keyOf(user, source), source), s).onUnauthorized();
        return true;
    }

    /** Drops what is cached for a principal's source (logout, new login). */
    public void forget(String principalId, String source) {
        synchronized (providers) {
            providers.remove(new Key(principalId, source));
        }
    }

    /** Drops everything cached for a principal (user deleted). */
    public void forgetAll(String principalId) {
        synchronized (providers) {
            providers.keySet().removeIf(k -> Objects.equals(k.principal(), principalId));
        }
    }

    private AuthProvider provider(Key key, Source s) {
        synchronized (providers) {
            return providers.computeIfAbsent(key, k -> s.login().authProvider(store, k.principal()));
        }
    }

    /** The cached service token, renewed (single flight per source) when it is about to expire. */
    private OAuthToken serviceToken(Source s) {
        OAuthToken t = serviceTokens.get(s.name());
        if (t != null && !t.expiresWithin(clock.getAsLong(), SKEW_SECONDS)) return t;
        synchronized (s) {
            t = serviceTokens.get(s.name());
            if (t != null && !t.expiresWithin(clock.getAsLong(), SKEW_SECONDS)) return t;
            try {
                t = s.clientCredentials().get();
            } catch (OAuthException e) {
                throw new IllegalStateException("Service login of " + s.name() + " failed: " + e.getMessage(), e);
            }
            serviceTokens.put(s.name(), t);
            return t;
        }
    }
}

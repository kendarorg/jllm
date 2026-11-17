package org.kendar.llm.harness.llm;

import org.kendar.llm.harness.auth.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The credentials of one provider, resolved per call:
 * <pre>
 * userAuth null                          -> global key
 * no caller (no scope)                   -> global key only, never another user's credential
 * caller has a stored credential         -> use it (OAuth refreshed as needed); a 401 refreshes once and never falls
 *                                           back to the global key
 * allowGlobalFallback and a global key   -> global key
 * otherwise                              -> {@link LoginRequiredException} ({@code provider:<name>})
 * </pre>
 */
public final class ProviderAuth implements AuthSource {

    private final ProviderSettings provider;
    private final OAuthCredentialResolver resolver;
    private final String source;
    private final String type;
    private final String globalKey;

    /** @param resolver the resolver of the harness; may be null when the provider has no per-user credential */
    public ProviderAuth(ProviderSettings provider, OAuthCredentialResolver resolver) {
        if (provider.perUser() && (resolver == null || !resolver.handles(provider.source()))) {
            throw new IllegalArgumentException("Provider '" + provider.name() + "' has userAuth but no credential source");
        }
        this.provider = provider;
        this.resolver = resolver;
        this.source = provider.source();
        this.type = provider.effectiveType();
        this.globalKey = provider.globalKey();
    }

    /** Whether the provider needs an {@link AuthSource}: per-user credentials or extra headers. */
    public static boolean needed(ProviderSettings p) {
        return p.perUser() || !p.headers().isEmpty();
    }

    @Override
    public AuthProvider bind(CallerScope.Caller caller) {
        return new Bound(caller);
    }

    private final class Bound implements AuthProvider {
        private final CallerScope.Caller caller;
        private volatile boolean usedUser;

        Bound(CallerScope.Caller caller) {
            this.caller = caller;
        }

        @Override
        public Map<String, String> headers() {
            Map<String, String> h = new LinkedHashMap<>(provider.headers());
            if (!provider.perUser()) {
                addGlobal(h);
                return h;
            }
            if (caller != null && resolver.connected(caller.principal(), source)) {
                usedUser = true;
                h.putAll(resolver.headers(caller.principal(), source));
                return h;
            }
            usedUser = false;
            if ((caller == null || provider.fallsBackToGlobal()) && !globalKey.isBlank()) {
                addGlobal(h);
                return h;
            }
            throw new LoginRequiredException(source);
        }

        private void addGlobal(Map<String, String> h) {
            if (!globalKey.isBlank()) h.putAll(ProviderTokenLogin.header(type, globalKey));
        }

        @Override
        public void onUnauthorized() {
            if (usedUser) resolver.onUnauthorized(caller.principal(), source);
        }
    }
}

package org.kendar.llm.harness.auth;

/**
 * The authentication of a model provider, bound to the caller of each call. A model client reads the
 * {@link CallerScope} once at the entry of a call and binds it here; the returned provider serves the headers of every
 * request and retry of that call, so they never depend on the thread of the HTTP layer.
 */
@FunctionalInterface
public interface AuthSource {

    /**
     * @param caller the caller of the call; null when it has no user (global key only)
     * @return the provider of the call's headers; {@link AuthProvider#headers()} throws
     *         {@link LoginRequiredException} when the caller must connect an account first
     */
    AuthProvider bind(CallerScope.Caller caller);
}

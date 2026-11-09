package org.kendar.llm.harness.auth;

/**
 * A login for one provider, discovered with {@link java.util.ServiceLoader}; {@code /login <name>} runs it and
 * {@code /logout <name>} removes the stored token. A provider is this interface over the generic flows
 * ({@link AuthCodeLogin}, {@link DeviceCodeLogin}) plus its endpoints and header shape.
 */
public interface OAuthLogin {

    String name();

    /** Runs the flow and returns the token; the caller stores it. @throws OAuthException on failure */
    OAuthToken login(UserInteraction ui);

    /** Headers for requests of this provider, refreshing the stored token as needed. */
    AuthProvider authProvider(TokenStore store);
}

package org.kendar.llm.harness.auth;

/**
 * A login for one provider or source, discovered with {@link java.util.ServiceLoader} (providers) or built from the
 * settings ({@code api:<name>}, {@code mcp:<server>}); {@code /login <name>} runs it and {@code /logout <name>} removes
 * the stored token. A provider is this interface over the generic flows ({@link AuthCodeLogin},
 * {@link DeviceCodeLogin}) plus its endpoints and header shape.
 */
public interface OAuthLogin {

    String name();

    /** Runs the flow with a loopback redirect and returns the token; the caller stores it. @throws OAuthException */
    OAuthToken login(UserInteraction ui);

    /**
     * Runs the flow with the redirect sent to {@code target} (a web callback URL, or a loopback listener). Flows
     * without a redirect (device code) ignore it.
     *
     * @throws OAuthException on failure, code {@code unsupported} when this login cannot use a web redirect
     */
    default OAuthToken login(UserInteraction ui, RedirectTarget target) {
        if (target == null || target.isLoopback()) return login(ui);
        throw new OAuthException("unsupported", name() + " cannot log in through a web redirect");
    }

    /** Headers for requests of one principal (null: the single user / shared login). */
    AuthProvider authProvider(TokenStore store, String principalId);
}

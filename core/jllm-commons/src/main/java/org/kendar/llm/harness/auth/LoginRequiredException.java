package org.kendar.llm.harness.auth;

/**
 * The caller has no usable token for an OAuth source (never logged in, logged out, or the refresh token was
 * rejected). Thrown by a {@link CredentialResolver}; the agent loop turns it into a tool error that asks the user to
 * connect the source, plus a {@code login_required} event. The message never contains a token.
 */
public class LoginRequiredException extends RuntimeException {

    private final String source;

    /** @param source "api:&lt;name&gt;" or "mcp:&lt;server&gt;" */
    public LoginRequiredException(String source) {
        this(source, null);
    }

    public LoginRequiredException(String source, Throwable cause) {
        super("Not connected to " + source + ". Ask the user to connect it (/login " + source + "), then retry.", cause);
        this.source = source;
    }

    public String source() {
        return source;
    }
}

package org.kendar.llm.harness.auth;

/** An OAuth error response ({@code error}, {@code error_description}) or a transport failure (code {@code io}). */
public class OAuthException extends RuntimeException {

    private final String code;

    public OAuthException(String code, String description) {
        super(description == null || description.isBlank() ? code : code + ": " + description);
        this.code = code;
    }

    public OAuthException(String code, String description, Throwable cause) {
        super(description == null || description.isBlank() ? code : code + ": " + description, cause);
        this.code = code;
    }

    /** The OAuth error code, e.g. {@code invalid_grant}, {@code authorization_pending}, {@code slow_down}. */
    public String code() {
        return code;
    }
}

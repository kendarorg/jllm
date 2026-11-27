package org.kendar.llm.web.commons.auth;

import java.util.Set;

/** A refused sign-in. {@link #code} is one of {@link #CODES}; the message is for the log and never reaches the page. */
public final class OidcException extends Exception {

    public static final Set<String> CODES = Set.of("denied", "invalid_token", "unknown_user", "domain_not_allowed",
            "email_not_verified", "expired", "no_role");

    private final String code;

    public OidcException(String code, String detail) {
        super(detail);
        if (!CODES.contains(code)) throw new IllegalArgumentException("Unknown code " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

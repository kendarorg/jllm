package org.kendar.llm.harness.auth;

import java.util.Map;

/**
 * Headers for one outgoing MCP or REST call, resolved per call (tokens expire and refresh).
 * Headers from the settings file are always sent; these are added on top.
 */
@FunctionalInterface
public interface CredentialResolver {

    /** Adds nothing: the single-user default. */
    CredentialResolver NONE = (user, source) -> Map.of();

    /**
     * @param user   the chat's principal; null when the chat has none
     * @param source "mcp:&lt;server&gt;" or "api:&lt;name&gt;"
     */
    Map<String, String> headers(HarnessPrincipal user, String source);
}

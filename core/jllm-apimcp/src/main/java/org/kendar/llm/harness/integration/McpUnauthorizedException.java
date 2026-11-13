package org.kendar.llm.harness.integration;

import java.io.IOException;

/**
 * The MCP server answered 401 to a call made with the caller's own credentials (per-user OAuth). The caller refreshes
 * them and retries once; the client's shared login is not involved.
 */
public final class McpUnauthorizedException extends IOException {

    public McpUnauthorizedException(String message) {
        super(message);
    }
}

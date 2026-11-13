package org.kendar.llm.harness.integration;

import java.io.IOException;

/**
 * The MCP server of a client stopped working and could not be recovered (an HTTP session could not be
 * re-initialized, a stdio server could not be restarted). The client refuses every later call: its source should be
 * disabled. Clients also report this once through their {@code onUnavailable} callback.
 */
public final class McpUnavailableException extends IOException {

    public McpUnavailableException(String message) {
        super(message);
    }

    public McpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

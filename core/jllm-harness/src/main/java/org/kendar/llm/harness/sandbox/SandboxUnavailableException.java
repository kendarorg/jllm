package org.kendar.llm.harness.sandbox;

/**
 * The container sandbox cannot be used (docker missing, not running, or remote). The agent loop catches it and asks the
 * user whether to continue without isolation ({@link SandboxExecutor#allowLocalFallback}) instead of failing the tool.
 */
public class SandboxUnavailableException extends RuntimeException {

    public SandboxUnavailableException(String message) {
        super(message);
    }
}

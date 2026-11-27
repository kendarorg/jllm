package org.kendar.llm.eval.fixture;

/**
 * One request received by a fake server.
 *
 * @param seq    1-based order of arrival
 * @param kind   {@code call} for a tool call or an API operation, {@code protocol} for discovery and handshakes
 * @param name   tool name, operation id, or the protocol method
 * @param method HTTP method
 * @param path   request path
 * @param body   request body ({@code ""} when none)
 * @param status HTTP status answered
 * @param down   true when the server was simulating being down
 */
public record ServerCall(int seq, String kind, String name, String method, String path, String body, int status,
                         boolean down) {

    public boolean isCall() {
        return "call".equals(kind);
    }
}

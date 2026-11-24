package org.kendar.llm.harness.agent;

/**
 * A sub-agent ended without a final answer (cut off, refused, out of turns, cancelled).
 *
 * @see #status() {@code partial}, {@code refused}, {@code cancelled}, {@code stuck} or {@code unverified}
 */
public final class SubAgentIncompleteException extends RuntimeException {
    private final String status;
    private final String partial;

    public SubAgentIncompleteException(String status, String partial, String warning) {
        super(warning);
        this.status = status;
        this.partial = partial == null ? "" : partial;
    }

    public String status() {
        return status;
    }

    /** What the sub-agent wrote before it stopped (may be empty). */
    public String partial() {
        return partial;
    }
}

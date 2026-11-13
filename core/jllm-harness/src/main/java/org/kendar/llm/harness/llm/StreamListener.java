package org.kendar.llm.harness.llm;

/**
 * Receives the parts of a model response while it streams in. Called on the thread that makes the call, in order.
 * The final {@link ModelResponse} is still returned as a whole and is what counts; the deltas are for display.
 *
 * <p>{@code block} is the index of the content block the delta belongs to.
 */
public interface StreamListener {

    /** Ignores everything. */
    StreamListener NONE = new StreamListener() {
    };

    default void onText(int block, String delta) {
    }

    default void onThinking(int block, String delta) {
    }

    /**
     * A piece of the arguments of a tool call. {@code id} and {@code name} may be null after the first piece of a
     * block.
     */
    default void onToolCall(int block, String id, String name, String argsDelta) {
    }

    /** The call failed after deltas were delivered and is being retried as a whole: drop what was shown. */
    default void onReset() {
    }
}

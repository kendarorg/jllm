package org.kendar.llm.harness.context;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * The messages of the next request.
 *
 * @param messages   what to send; may be the log itself
 * @param changed    the request differs from the previous one by more than appended messages: the last measured
 *                   usage no longer describes it
 * @param logChanged the log itself was rewritten and must be saved
 */
public record ContextView(ArrayNode messages, boolean changed, boolean logChanged) {

    /** The log as it is. */
    public static ContextView of(ArrayNode log) {
        return new ContextView(log, false, false);
    }
}

package org.kendar.llm.eval.run;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.llm.Usage;

import java.util.List;

/**
 * One request sent to the model, deep-copied, with the usage of its response ({@code null} when unknown).
 *
 * @param simple true for single-shot summary/classifier calls ({@code simpleComplete}); messages then hold the prompt
 */
public record RecordedRequest(int index, boolean simple, String pinnedSystem, String extraSystem, ArrayNode messages,
                              List<String> toolNames, int maxTokens, Usage usage, long millis) {

    /** The part of the request that precedes the messages, as the cache sees it. */
    public String prefix() {
        return (pinnedSystem == null ? "" : pinnedSystem) + "\u0000" + (extraSystem == null ? "" : extraSystem)
                + "\u0000" + String.join(",", toolNames);
    }
}

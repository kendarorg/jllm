package org.kendar.llm.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.context.mnemonic.SegmentSwitcher;
import org.kendar.llm.harness.llm.Usage;

import java.util.List;

/**
 * One request sent to the model, deep-copied, with the usage of its response ({@code null} when unknown).
 *
 * @param simple true for side calls outside the agent loop: single-shot summary/classifier calls ({@code simpleComplete},
 *               messages then hold the prompt) and the mnemonic handoff-notes request ({@link #handoffNotes})
 */
public record RecordedRequest(int index, boolean simple, String pinnedSystem, String extraSystem, ArrayNode messages,
                              List<String> toolNames, int maxTokens, Usage usage, long millis) {

    /** True for the mnemonic handoff-notes request: the main prefix plus the handoff instruction as last text block. */
    public static boolean handoffNotes(ArrayNode messages) {
        if (messages.isEmpty()) return false;
        JsonNode c = messages.get(messages.size() - 1).path("content");
        return c.isArray() && !c.isEmpty() && SegmentSwitcher.HANDOFF.equals(c.get(c.size() - 1).path("text").asText(null));
    }

    /** The part of the request that precedes the messages, as the cache sees it. */
    public String prefix() {
        return (pinnedSystem == null ? "" : pinnedSystem) + "\u0000" + (extraSystem == null ? "" : extraSystem)
                + "\u0000" + String.join(",", toolNames);
    }
}

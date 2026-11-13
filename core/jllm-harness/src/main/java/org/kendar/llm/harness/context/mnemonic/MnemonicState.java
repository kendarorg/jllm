package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted decisions of one agent: what is masked and where the current segment starts. Only ever appended to, so
 * the rendered prompt of a given log never changes once decided. Decisions referring to a message whose content
 * hash no longer matches the log (the log was edited or cleared) are dropped, each on its own.
 */
public final class MnemonicState {

    /** Kinds of {@link Mask}. */
    static final String TOOL_RESULT = "tool_result";
    static final String TOOL_INPUT = "tool_input";
    static final String THINKING = "thinking";

    /**
     * One masked content block.
     *
     * @param index       message index in the log
     * @param hash        content hash of that message when the decision was made
     * @param block       index of the block in the message's content array
     * @param kind        {@value #TOOL_RESULT}, {@value #TOOL_INPUT} or {@value #THINKING}
     * @param id          recall id
     * @param replacement what the block's content (tool_result) or input (tool_use) becomes; null for reasoning,
     *                    which is dropped
     * @param saved       estimated tokens saved
     */
    public record Mask(int index, String hash, int block, String kind, String id, JsonNode replacement, long saved) {
    }

    /**
     * Start of a segment: the messages before {@code cut} are replaced by the handoff notes.
     *
     * @param firstUser   index of the agent's first user message (a sub-agent's own task, else the chat's first
     *                    request), kept verbatim at the top; -1 if none
     * @param instruction index of the user instruction in progress when the cut falls after it, kept verbatim after
     *                    the first request; null when it stays in the tail (or in files written before it existed)
     */
    public record Segment(int cut, String cutHash, int firstUser, String firstUserHash, String notes, String index,
                          Integer instruction, String instructionHash) {

        public Segment(int cut, String cutHash, int firstUser, String firstUserHash, String notes, String index) {
            this(cut, cutHash, firstUser, firstUserHash, notes, index, null, null);
        }
    }

    public final List<Mask> masks = new ArrayList<>();
    /** New masks only at or after this message index: the first masked message of the last batch. */
    public int frontier;
    public final List<Segment> segments = new ArrayList<>();
    /** Set by a store that could not read the saved state and started over; reported once as a warning event. */
    @JsonIgnore
    public volatile String warning;

    @JsonIgnore
    Segment current() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    @JsonIgnore
    int start() {
        Segment s = current();
        return s == null ? 0 : s.cut();
    }

    boolean masked(int index, int block) {
        for (Mask m : masks) if (m.index() == index && m.block() == block) return true;
        return false;
    }

    Mask byId(String id) {
        for (Mask m : masks) if (m.id().equals(id)) return m;
        return null;
    }

    /** Drops every decision about messages at or after {@code index}. @return true if anything was dropped */
    boolean invalidateFrom(int index) {
        int before = masks.size() + segments.size();
        masks.removeIf(m -> m.index() >= index);
        segments.removeIf(s -> s.cut() >= index || s.firstUser() >= index
                || (s.instruction() != null && s.instruction() >= index));
        frontier = Math.min(frontier, index);
        return masks.size() + segments.size() != before;
    }
}

package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.context.TokenEstimate;

/**
 * Where a message log may be cut, and the thinking-block rules that go with a rewrite. Pure: nothing here calls a
 * model; the only mutation is {@link #stripThinking}. Cuts only ever fall on a plain user text message, so a tool_use
 * is never separated from its tool_result.
 */
public final class CutPlanner {

    /** Stands in for an assistant message that held nothing but reasoning once the reasoning is dropped. */
    public static final String REASONING_OMITTED = "[reasoning omitted]";

    private CutPlanner() {
    }

    /** The older tool rounds {@code (prompt, cut)} of the turn in progress, to be summarized into message {@code cut}. */
    public record InTurnCut(int prompt, int cut) { }

    /**
     * Index of the plain-user-text message that starts the {@code keepTurns}-th most recent turn, so a cut there
     * never separates a tool_use from its tool_result. 0 when there are not enough turns to cut.
     */
    public static int cutIndex(ArrayNode messages, int keepTurns) {
        int turns = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isPlainUserText(messages.get(i)) && ++turns == Math.max(1, keepTurns)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Start of the newest run of whole turns that fits in {@code keepTokens}: what stays verbatim beside the
     * summary. The latest turn is always kept, even when it alone is larger.
     */
    public static int tokenCutIndex(ArrayNode messages, int keepTokens) {
        long size = 0;
        int cut = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            size += TokenEstimate.tokens(messages.get(i).toString().length());
            if (!isPlainUserText(messages.get(i))) continue;
            if (size > keepTokens) break;
            cut = i;
        }
        return cut >= 0 ? cut : cutIndex(messages, 1);
    }

    /** The span [0, cut) is only an earlier summary: summarizing it again adds nothing. */
    public static boolean onlySummary(ArrayNode messages, int cut) {
        return cut == 1 && SummaryBuilder.startsWithSummary(messages);
    }

    /**
     * Cut inside the turn in progress: the earliest assistant message {@code k} after a user message whose tail fits
     * both {@code keepTokens} and what {@code room} leaves (after the head and {@code summaryTokens}), else the latest
     * such message. Null when there is no turn in progress, only a single tool round, or the rounds before {@code k}
     * hold fewer than {@code minFreed} tokens (a summary would not be smaller).
     */
    public static InTurnCut inTurnCut(ArrayNode messages, long room, int keepTokens, long summaryTokens, long minFreed) {
        int p = cutIndex(messages, 1);
        int n = messages.size();
        if (p >= n || !isPlainUserText(messages.get(p))) return null;
        long head = 0;
        for (int i = 0; i <= p; i++) head += TokenEstimate.tokens(messages.get(i).toString().length());
        long target = Math.min(keepTokens, room - head - summaryTokens);
        long[] suffix = new long[n + 1];
        for (int i = n - 1; i > p; i--) suffix[i] = suffix[i + 1] + TokenEstimate.tokens(messages.get(i).toString().length());
        int k = -1;
        int latest = -1;
        for (int i = p + 2; i < n; i++) {
            if (!"assistant".equals(messages.get(i).path("role").asText())
                    || !"user".equals(messages.get(i - 1).path("role").asText())) continue;
            latest = i;
            if (k < 0 && suffix[i] <= target) k = i;
        }
        if (k < 0) k = latest;
        if (k < 0) return null;
        if (suffix[p + 1] - suffix[k] < minFreed) return null;
        return new InTurnCut(p, k);
    }

    public static boolean isPlainUserText(JsonNode msg) {
        if (!"user".equals(msg.path("role").asText())) return false;
        JsonNode c = msg.path("content");
        if (c.isTextual()) return true;
        if (!c.isArray() || c.isEmpty()) return false;
        for (JsonNode b : c) {
            if (!"text".equals(b.path("type").asText())) return false;
        }
        return true;
    }

    /**
     * Anthropic binds a thinking block's signature to the content before it; once earlier content is rewritten the
     * block is rejected (400 "Invalid signature"). Reasoning of finished turns is not needed, so drop it.
     *
     * @param includeCurrent also drop the blocks of the turn in progress; the caller must then stop requesting
     *                       thinking for the rest of that turn (the API wants them in a tool loop while it is on)
     */
    public static void stripThinking(ArrayNode messages, boolean includeCurrent) {
        int cut = includeCurrent ? messages.size() : cutIndex(messages, 1);
        for (int i = 0; i < cut; i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText()) || !m.path("content").isArray()) continue;
            ArrayNode content = (ArrayNode) m.path("content");
            boolean dropped = false;
            for (int j = content.size() - 1; j >= 0; j--) {
                if (isThinkingBlock(content.get(j))) {
                    content.remove(j);
                    dropped = true;
                }
            }
            // an assistant message cannot be empty: one that only held reasoning keeps a placeholder
            if (dropped && content.isEmpty()) content.addObject().put("type", "text").put("text", REASONING_OMITTED);
        }
    }

    /** True when the turn in progress (after the last plain user message) holds a thinking block. */
    public static boolean currentTurnHasThinking(ArrayNode messages) {
        for (int i = cutIndex(messages, 1); i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText()) || !m.path("content").isArray()) continue;
            for (JsonNode b : m.path("content")) if (isThinkingBlock(b)) return true;
        }
        return false;
    }

    private static boolean isThinkingBlock(JsonNode b) {
        String t = b.path("type").asText();
        return "thinking".equals(t) || "redacted_thinking".equals(t);
    }
}

package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Mask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Level 1: chooses the content blocks a masking batch replaces with receipts, oldest first. */
final class MaskPlanner {

    /** Marks a receipt; the receipt guard looks for it in tool inputs. */
    static final String MARKER = "recall id=";

    /**
     * @param minMaskTokens    smaller blocks are never masked
     * @param keepRecentTokens newest tokens never masked
     * @param minAgeTurns      blocks younger than this many assistant messages are never masked
     * @param dropOldThinking  reasoning of earlier user turns may be dropped
     */
    record Limits(long minMaskTokens, long keepRecentTokens, int minAgeTurns, boolean dropOldThinking) {
    }

    private final Limits limits;

    MaskPlanner(Limits limits) {
        this.limits = limits;
    }

    /**
     * Picks blocks oldest first until {@code current} drops to {@code target}; nothing is chosen unless the batch
     * frees at least {@code minBatch}. Blocks before {@code from} (frontier, segment start, inherited copy) and in the
     * protected tail are never touched.
     *
     * @param applied messages {@code from..} as currently rendered, aligned with the log
     * @return the new masks; empty when no batch is worth it
     */
    List<Mask> batch(ArrayNode log, MnemonicState st, int from, List<JsonNode> applied, int appliedStart,
                     long current, long target, long minBatch) {
        int n = log.size();
        int limit = Math.min(protectedFrom(applied, appliedStart, n), ageFrom(log));
        int lastUser = lastPlainUser(log);
        List<Mask> chosen = new ArrayList<>();
        long saved = 0;
        for (int i = Math.max(from, 0); i < limit && current - saved > target; i++) {
            for (Mask m : candidates(log, st, i, i < lastUser)) {
                chosen.add(m);
                saved += m.saved();
                if (current - saved <= target) break;
            }
        }
        return saved >= minBatch ? chosen : List.of();
    }

    /**
     * Emergency: the newest tool results alone leave no room for the response. Masks the largest results of the
     * trailing tool-result message, protection ignored, but only if that frees at least {@code needed}.
     */
    List<Mask> emergency(ArrayNode log, MnemonicState st, long needed) {
        int i = log.size() - 1;
        if (i < 0 || !"user".equals(Messages.role(log.get(i)))) return List.of();
        List<Mask> all = new ArrayList<>(candidates(log, st, i, false));
        all.removeIf(m -> !MnemonicState.TOOL_RESULT.equals(m.kind()));
        all.sort(Comparator.comparingLong(Mask::saved).reversed());
        List<Mask> chosen = new ArrayList<>();
        long saved = 0;
        for (Mask m : all) {
            if (saved >= needed) break;
            chosen.add(m);
            saved += m.saved();
        }
        return saved >= needed ? chosen : List.of();
    }

    /** Maskable blocks of message {@code i}, in block order. */
    private List<Mask> candidates(ArrayNode log, MnemonicState st, int i, boolean earlierUserTurn) {
        JsonNode msg = log.get(i);
        JsonNode content = msg.path("content");
        if (!content.isArray()) return List.of();
        String hash = null;
        List<Mask> out = new ArrayList<>();
        int nonThinking = 0;
        for (JsonNode b : content) if (!Messages.isThinking(b)) nonThinking++;
        for (int k = 0; k < content.size(); k++) {
            if (st.masked(i, k)) continue;
            JsonNode b = content.get(k);
            String type = b.path("type").asText();
            if (hash == null) hash = Messages.hash(i, msg);
            if ("tool_result".equals(type)) {
                String text = Messages.resultText(b);
                long size = Messages.tokens(b.path("content"));
                if (size < limits.minMaskTokens()) continue;
                String id = "r" + id(hash, k);
                JsonNode use = Messages.toolUse(log, i, b.path("tool_use_id").asText());
                String receipt = "[masked: " + (b.path("is_error").asBoolean(false) ? "error from " : "")
                        + Messages.callSummary(use) + " -> ~" + Messages.tokens(text) + " tokens, " + MARKER + id + "]";
                TextNode r = TextNode.valueOf(receipt);
                long saved = size - Messages.tokens(r);
                if (saved > 0) out.add(new Mask(i, hash, k, MnemonicState.TOOL_RESULT, id, r, saved));
            } else if ("tool_use".equals(type) && b.path("input").isObject()) {
                String id = "a" + id(hash, k);
                ObjectNode input = (ObjectNode) b.path("input");
                ObjectNode replaced = input.deepCopy();
                boolean any = false;
                for (Iterator<Map.Entry<String, JsonNode>> it = input.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> f = it.next();
                    if (!f.getValue().isTextual()) continue;
                    long size = Messages.tokens(f.getValue().asText());
                    if (size < limits.minMaskTokens()) continue;
                    replaced.put(f.getKey(), "[masked ~" + size + " tokens, " + MARKER + id + "]");
                    any = true;
                }
                long saved = Messages.tokens(input) - Messages.tokens(replaced);
                if (any && saved > 0) out.add(new Mask(i, hash, k, MnemonicState.TOOL_INPUT, id, replaced, saved));
            } else if (Messages.isThinking(b) && limits.dropOldThinking() && earlierUserTurn && nonThinking > 0) {
                // reasoning may only be dropped from earlier user turns, never from the open tool-use chain
                long size = Messages.tokens(b);
                if (size < limits.minMaskTokens()) continue;
                out.add(new Mask(i, hash, k, MnemonicState.THINKING, "t" + id(hash, k), null, size));
            }
        }
        return out;
    }

    /** Index of the oldest message of the protected tail (the message straddling the boundary included). */
    private int protectedFrom(List<JsonNode> applied, int appliedStart, int n) {
        long acc = 0;
        int from = n;
        for (int i = n - 1; i >= appliedStart; i--) {
            acc += Messages.tokens(applied.get(i - appliedStart));
            from = i;
            if (acc >= limits.keepRecentTokens()) break;
        }
        return acc >= limits.keepRecentTokens() ? from : appliedStart;
    }

    /** Messages at or after the returned index are younger than {@code minAgeTurns} assistant turns. */
    private int ageFrom(ArrayNode log) {
        if (limits.minAgeTurns() <= 0) return log.size();
        int assistants = 0;
        for (int i = log.size() - 1; i >= 0; i--) {
            if ("assistant".equals(Messages.role(log.get(i))) && ++assistants == limits.minAgeTurns()) return i;
        }
        return 0;
    }

    static int lastPlainUser(ArrayNode log) {
        for (int i = log.size() - 1; i >= 0; i--) if (Messages.isPlainUserText(log.get(i))) return i;
        return -1;
    }

    /** 12 hex chars identifying block {@code k} of the message with content hash {@code hash}. */
    static String id(String hash, int k) {
        return Messages.sha256(hash + ":" + k).substring(0, 12);
    }
}

package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.agent.EventSink;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.context.TokenEstimate;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Tiered, cheapest-first compaction of the message log. Operates on messages only: the pinned
 * chatId+agentId system block is sent separately and is never part of what is rewritten here.
 * <ol>
 *   <li>microcompact: blank old tool results (no LLM)</li>
 *   <li>summarize older turns (LLM)</li>
 *   <li>propose an aggressive rewrite and wait for the user to approve/edit it</li>
 * </ol>
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);
    static final String CLEARED = "[tool result cleared to save context]";
    private static final String SUMMARY_PREFIX = "[Summary of the earlier conversation]\n";
    /** Starts the summary of the older tool rounds of the turn in progress (see {@link #compactOverflow}). */
    static final String TURN_SUMMARY_PREFIX = "[Summary of the earlier steps of this request]\n";
    /** Stands in for an assistant message that held nothing but reasoning once the reasoning is dropped. */
    static final String REASONING_OMITTED = "[reasoning omitted]";
    private static final String OMITTED = "[earlier history omitted]\n";
    private static final int SUMMARY_TOKENS = 3000;
    /** Highest {@link #render(ArrayNode, int)} level: user text only. */
    private static final int MAX_SHRINK = 3;
    /** Attempt n of a too-long summary request is cut to this share of the first refused size (attempt 0: whole). */
    private static final double[] SHRINK = {1.0, 0.85, 0.70, 0.50, 0.35};

    private final HarnessConfig config;
    private final ModelClient model;
    private final ApprovalService approvals;
    private final ObjectMapper mapper;
    /** chatId → estimated size at which the user last declined a tier-3 rewrite. */
    private final Map<String, Long> declined = new ConcurrentHashMap<>();

    private final HarnessMetrics metrics;

    public ContextManager(HarnessConfig config, ModelClient model, ApprovalService approvals, ObjectMapper mapper) {
        this(config, model, approvals, mapper, HarnessMetrics.noop());
    }

    public ContextManager(HarnessConfig config, ModelClient model, ApprovalService approvals, ObjectMapper mapper,
                          HarnessMetrics metrics) {
        this.metrics = metrics;
        this.config = config;
        this.model = model;
        this.approvals = approvals;
        this.mapper = mapper;
    }

    public long estimateTokens(ArrayNode messages, Usage lastUsage) {
        long chars = TokenEstimate.tokens(messages.toString().length());
        return lastUsage == null ? chars : Math.max(chars, lastUsage.contextTokens());
    }

    /** Tokens of the fixed part of a request (system text and tool schemas), estimated from its characters. */
    public static long fixedTokens(RequestPrefix prefix) {
        return TokenEstimate.tokens(fixedChars(prefix));
    }

    /** Characters of the fixed part of a request (system text and tool schemas). */
    public static long fixedChars(RequestPrefix prefix) {
        if (prefix == null) return 0;
        long chars = (prefix.pinned() == null ? 0 : prefix.pinned().length())
                + (prefix.extra() == null ? 0 : prefix.extra().length());
        if (prefix.tools() != null) {
            for (HarnessTool tool : prefix.tools()) {
                chars += tool.name().length() + tool.description().length() + tool.inputSchema().toString().length();
            }
        }
        return chars;
    }

    /**
     * True when the whole request (messages plus the fixed part) is over {@code budget}: by the estimate corrected
     * with {@code ratio}, or as the provider measured it.
     */
    private boolean over(ArrayNode messages, Usage lastUsage, RequestPrefix prefix, long budget, double ratio) {
        long est = estimateTokens(messages, null) + fixedTokens(prefix);
        return est > scaled(budget, ratio) || (lastUsage != null && lastUsage.contextTokens() > budget);
    }

    /** {@code budget} in the unit of the uncorrected estimate. */
    private static long scaled(long budget, double ratio) {
        return ratio > 0 ? (long) (budget / ratio) : budget;
    }

    /** The span [0, cut) is only an earlier summary: summarizing it again adds nothing. */
    private static boolean onlySummary(ArrayNode messages, int cut) {
        return cut == 1 && startsWithSummary(messages);
    }

    /** Rewrites {@code messages} in place when over budget. @return true if anything changed. */
    public boolean compactIfNeeded(String chatId, ArrayNode messages, Usage lastUsage,
                                   EventSink sink, BooleanSupplier cancelled) {
        return compactIfNeeded(chatId, messages, lastUsage, sink, cancelled, metrics::tokens);
    }

    /** @param usageSink receives the tokens spent by summarization calls (so they count towards session usage) */
    public boolean compactIfNeeded(String chatId, ArrayNode messages, Usage lastUsage,
                                   EventSink sink, BooleanSupplier cancelled, java.util.function.Consumer<Usage> usageSink) {
        return compactIfNeeded(chatId, messages, lastUsage, sink, cancelled, usageSink, true);
    }

    /**
     * Compacts when the prompt reaches the ceiling derived from the context window (nothing happens with
     * {@code compaction.auto} off).
     *
     * @param allowRewrite tier 3 needs the user's approval: false for agents that run unattended (sub-agents)
     */
    public boolean compactIfNeeded(String chatId, ArrayNode messages, Usage lastUsage, EventSink sink,
                                   BooleanSupplier cancelled, java.util.function.Consumer<Usage> usageSink,
                                   boolean allowRewrite) {
        return compactIfNeeded(chatId, messages, lastUsage, sink, cancelled, usageSink, allowRewrite, null);
    }

    public boolean compactIfNeeded(String chatId, ArrayNode messages, Usage lastUsage, EventSink sink,
                                   BooleanSupplier cancelled, java.util.function.Consumer<Usage> usageSink,
                                   boolean allowRewrite, RequestPrefix prefix) {
        return compactIfNeeded(chatId, messages, lastUsage, sink, cancelled, usageSink, allowRewrite, prefix, 1.0);
    }

    /** @param ratio measured tokens / estimated tokens of the agent's requests (see {@link TokenEstimate}) */
    public boolean compactIfNeeded(String chatId, ArrayNode messages, Usage lastUsage, EventSink sink,
                                   BooleanSupplier cancelled, java.util.function.Consumer<Usage> usageSink,
                                   boolean allowRewrite, RequestPrefix prefix, double ratio) {
        if (!config.compaction().auto()) return false;
        // rewriting history invalidates the signed thinking blocks of the turn in progress: optionally wait for it to end
        if (config.compaction().deferOnThinkingOverflow() && currentTurnHasThinking(messages)) return false;
        boolean changed = compactTo(chatId, messages, lastUsage, sink, cancelled, usageSink, allowRewrite,
                config.compaction().ceiling(config.contextBudgetTokens()), prefix, ratio);
        if (changed) stripThinking(messages, true);
        return changed;
    }

    /**
     * Compaction for an agent that started from a copy of its parent's context: {@code messages[0..inherited[0])} is the
     * copy, the rest is the agent's own. The agent's own messages are compacted first, to what is left of the ceiling
     * after the copy; only if the whole still does not fit is the copy summarized (into a user/assistant pair, so
     * {@code inherited[0]} becomes 2). Runs unattended: no user-approved rewrite. The parent's context is not touched.
     */
    public boolean compactInherited(String chatId, ArrayNode messages, int[] inherited, Usage lastUsage, EventSink sink,
                                    java.util.function.Consumer<Usage> usageSink, RequestPrefix prefix) {
        return compactInherited(chatId, messages, inherited, lastUsage, sink, usageSink, prefix, false);
    }

    /**
     * @param force compact even under the ceiling (provider overflow, manual compaction): the agent's own messages get
     *              the {@link #compactOverflow overflow} treatment, and the copy is summarized too when that changed
     *              nothing or was not enough. The copy boundary is kept either way.
     */
    public boolean compactInherited(String chatId, ArrayNode messages, int[] inherited, Usage lastUsage, EventSink sink,
                                    java.util.function.Consumer<Usage> usageSink, RequestPrefix prefix, boolean force) {
        return compactInherited(chatId, messages, inherited, lastUsage, sink, usageSink, prefix, force,
                config.compaction().ceiling(config.contextBudgetTokens()), 1.0, null);
    }

    /**
     * @param budget   the whole request must fit in this
     * @param ratio    measured tokens / estimated tokens of the agent's requests (see {@link TokenEstimate})
     * @param original the same messages before the caller cleared tool results in them (same indexes), so the summary
     *                 of the copy sees the results; null: {@code messages} as they are now
     */
    public boolean compactInherited(String chatId, ArrayNode messages, int[] inherited, Usage lastUsage, EventSink sink,
                                    java.util.function.Consumer<Usage> usageSink, RequestPrefix prefix, boolean force,
                                    long budget, double ratio, ArrayNode original) {
        if ((!force && !config.compaction().auto()) || inherited[0] <= 0) {
            return compactIfNeeded(chatId, messages, lastUsage, sink, () -> false, usageSink, false, prefix, ratio);
        }
        if (!force && config.compaction().deferOnThinkingOverflow() && currentTurnHasThinking(messages)) return false;
        long ceiling = scaled(budget, ratio);
        long fixed = fixedTokens(prefix);
        if (!force && !over(messages, lastUsage, prefix, budget, ratio)) return false;
        ArrayNode originalCopy = original != null && original.size() == messages.size()
                ? slice(original, 0, inherited[0]) : null;
        ArrayNode copy = slice(messages, 0, inherited[0]);
        ArrayNode own = slice(messages, inherited[0], messages.size());
        boolean changed = false;
        long room = ceiling - fixed - estimateTokens(copy, null);
        boolean ownDone = room > 0;
        if (room > 0) {
            try {
                changed = force ? compactOverflow(own, room, usageSink).changed()
                        : compactTo(chatId, own, null, sink, () -> false, usageSink, false, room, null, 1.0);
            } catch (RuntimeException e) {
                log.warn("Compaction of the agent's own messages failed: {}", e.getMessage());
            }
        }
        boolean copyIsSummary = copy.size() == 2 && startsWithSummary(copy);
        if (estimateTokens(copy, null) + estimateTokens(own, null) + fixed > ceiling
                || (force && !changed && !copyIsSummary)) {
            try {
                String summary = summarizeSpan(copy, copy.size(), null, usageSink, null, originalCopy);
                if (summary == null) throw new IllegalStateException("Nothing to compact yet");
                copy = mapper.createArrayNode();
                copy.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                        .put("text", SUMMARY_PREFIX + summary);
                copy.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text")
                        .put("text", "Understood.");
                inherited[0] = 2;
                metrics.compaction(2);
                sink.emit("compaction", obj("tier", 2, "inherited", true));
                changed = true;
            } catch (RuntimeException e) {
                log.warn("Compaction of the inherited context failed: {}", e.getMessage());
            }
        }
        // the copy alone left no room for the agent's own messages: they get their turn now that it is a summary
        long left = ceiling - fixed - estimateTokens(copy, null);
        if (!ownDone && left > 0 && estimateTokens(own, null) > left) {
            try {
                changed |= force ? compactOverflow(own, left, usageSink).changed()
                        : compactTo(chatId, own, null, sink, () -> false, usageSink, false, left, null, 1.0);
            } catch (RuntimeException e) {
                log.warn("Compaction of the agent's own messages failed: {}", e.getMessage());
            }
        }
        if (changed) {
            messages.removeAll();
            messages.addAll(copy);
            messages.addAll(own);
            // the copy's thinking signatures were bound to a history that is now rewritten
            stripThinking(messages, true);
        }
        return changed;
    }

    /**
     * @param budget the whole request (messages + the fixed part of {@code prefix}) must fit in this
     * @param ratio  measured tokens / estimated tokens of the agent's requests
     */
    private boolean compactTo(String chatId, ArrayNode messages, Usage lastUsage, EventSink sink,
                              BooleanSupplier cancelled, java.util.function.Consumer<Usage> usageSink,
                              boolean allowRewrite, long budget, RequestPrefix prefix, double ratio) {
        if (!over(messages, lastUsage, prefix, budget, ratio)) return false;
        // the provider measured the request over the budget, whatever the estimate says
        boolean measuredOver = lastUsage != null && lastUsage.contextTokens() > budget;
        // what the messages alone may take: the checks below measure messages only
        long room = scaled(budget, ratio) - fixedTokens(prefix);
        // the log before tier 1: the provider has it cached, and the summaries must see the tool results tier 1 blanks
        ArrayNode original = messages.deepCopy();

        boolean changed = false;
        // tier 1
        int cleared = microcompact(messages, config.keepRecentTurns());
        if (cleared > 0) {
            changed = true;
            metrics.compaction(1);
            sink.emit("compaction", obj("tier", 1, "cleared", cleared));
        }
        // after tier 1 the old usage figure is stale: rely on the size estimate
        if (estimateTokens(messages, null) <= room && !(measuredOver && cleared == 0)) return changed;

        // tier 2
        boolean summarized = false;
        try {
            if (summarize(messages, config.keepRecentTurns(), usageSink, prefix, original)) {
                changed = true;
                summarized = true;
                metrics.compaction(2);
                sink.emit("compaction", obj("tier", 2, "estimatedTokens", estimateTokens(messages, null)));
            }
        } catch (RuntimeException e) {
            log.warn("Tier-2 compaction failed: {}", e.getMessage());
            sink.emit("warning", obj("message", "Context summarization failed: " + e.getMessage()));
        }
        long size = estimateTokens(messages, null);
        // still too large (one long turn has no turn boundary to cut at): summarize its older tool rounds
        if (size > room || (measuredOver && !changed)) {
            if (cutInsideTurn(messages, room, usageSink, SUMMARY_TOKENS)) {
                changed = true;
                metrics.compaction(2);
                size = estimateTokens(messages, null);
                sink.emit("compaction", obj("tier", 2, "inTurn", true));
            }
        }
        if (size <= room) return changed;

        // tier 3: user-approved aggressive rewrite
        if (!allowRewrite) return changed;
        Long declinedAt = declined.get(chatId);
        if (declinedAt != null && size < declinedAt * 1.2) return changed;
        int cut = cutIndex(messages, 1);
        if (cut <= 0) return changed;
        // only an earlier summary before the current turn: a rewrite helps when the rest would fit without it, not
        // when the turn in progress is what is too large
        if (onlySummary(messages, cut)
                && size - TokenEstimate.tokens(messages.get(0).toString().length()) > room) return changed;
        try {
            // without a tier-2 rewrite the indexes still match the uncleared log
            String proposed = summarizeSpan(messages, cut, null, usageSink, null, summarized ? null : original);
            if (proposed == null) return changed;
            ArrayNode preview = slice(messages, 0, cut);
            ApprovalService.ApprovalDecision d = approvals.await(chatId, "context",
                    "Context is over budget — approve a rewrite", render(preview), proposed,
                    a -> sink.emit("approval_request", mapper.valueToTree(a)), cancelled);
            if (d.approved()) {
                String text = d.edited() != null && !d.edited().isBlank() ? d.edited() : proposed;
                replaceSpan(messages, cut, text);
                declined.remove(chatId);
                changed = true;
                metrics.compaction(3);
                metrics.approval("context", true);
                sink.emit("compaction", obj("tier", 3, "estimatedTokens", estimateTokens(messages, null)));
            } else {
                metrics.approval("context", false);
                declined.put(chatId, size);
            }
        } catch (RuntimeException e) {
            sink.emit("warning", obj("message", "Context rewrite proposal failed: " + e.getMessage()));
        }
        return changed;
    }

    /**
     * Manual /compact: clears old tool results, then summarizes everything except the latest turn, regardless of the
     * token budget. No tier-3 approval. @param focus optional instructions on what the summary must preserve
     */
    public CompactResult compactNow(ArrayNode messages, String focus, java.util.function.Consumer<Usage> usageSink) {
        return compactNow(messages, focus, usageSink, null);
    }

    /** As above; with {@code prefix} the summary request reuses the normal request prefix (prompt cache). */
    public CompactResult compactNow(ArrayNode messages, String focus, java.util.function.Consumer<Usage> usageSink,
                                    RequestPrefix prefix) {
        return compactNow(messages, focus, usageSink, prefix, null);
    }

    /**
     * @param original the same messages before the caller cleared tool results in them (same indexes), so the summary
     *                 sees the results; null: {@code messages} as they are now
     */
    public CompactResult compactNow(ArrayNode messages, String focus, java.util.function.Consumer<Usage> usageSink,
                                    RequestPrefix prefix, ArrayNode original) {
        long before = estimateTokens(messages, null);
        // summarized from the log as it was before the tool results are cleared (same indexes)
        if (original == null || original.size() != messages.size()) original = messages.deepCopy();
        int cleared = microcompact(messages, 1);
        boolean summarized = false;
        int cut = cutIndex(messages, 1);
        if (cut > 0 && !onlySummary(messages, cut)) {
            String summary = summarizeSpan(messages, cut, focus, usageSink, prefix, original);
            if (summary != null) { // null: even the shortest form was rejected, nothing to compact yet
                replaceSpan(messages, cut, summary);
                summarized = true;
                metrics.compaction(2);
            }
        }
        if (cleared > 0 || summarized) stripThinking(messages, true);
        return new CompactResult(cleared > 0 || summarized, before, estimateTokens(messages, null), cleared);
    }

    /**
     * The provider rejected the request as too long. Like {@link #compactNow}, and when there is nothing before the
     * current prompt or what is left is still over {@code room}, the older tool rounds of the current turn are
     * summarized too: the prompt and the newest rounds stay verbatim, the summary goes in front of the first kept
     * round (an assistant message), so a tool call is never separated from its results.
     *
     * @param room tokens the messages may take
     */
    public CompactResult compactOverflow(ArrayNode messages, long room, java.util.function.Consumer<Usage> usageSink) {
        long before = estimateTokens(messages, null);
        ArrayNode original = messages.deepCopy();
        int cleared = microcompact(messages, 1);
        boolean summarized = false;
        int cut = cutIndex(messages, 1);
        if (cut > 0 && !onlySummary(messages, cut)) {
            try {
                String summary = summarizeSpan(messages, cut, null, usageSink, null, original);
                if (summary != null) {
                    replaceSpan(messages, cut, summary);
                    summarized = true;
                    metrics.compaction(2);
                }
            } catch (RuntimeException e) {
                log.warn("Summary of the earlier turns failed: {}", e.getMessage());
            }
        }
        if (!summarized || estimateTokens(messages, null) > room) {
            if (cutInsideTurn(messages, room, usageSink)) {
                summarized = true;
                metrics.compaction(2);
            }
        }
        boolean changed = cleared > 0 || summarized;
        if (changed) stripThinking(messages, true);
        return new CompactResult(changed, before, estimateTokens(messages, null), cleared);
    }

    /**
     * Summarizes the older tool rounds of the current turn: {@code [prompt+1, k)} where {@code k} is the earliest
     * assistant message after a user message whose tail fits both {@code keepTokens} and what {@code room} leaves,
     * else the latest such message. The summary becomes the first block of message {@code k}.
     *
     * @return true if the log was rewritten
     */
    public boolean cutInsideTurn(ArrayNode messages, long room, java.util.function.Consumer<Usage> usageSink) {
        return cutInsideTurn(messages, room, usageSink, 0);
    }

    /** @param minFreed nothing is done unless the summarized rounds hold at least this many tokens */
    public boolean cutInsideTurn(ArrayNode messages, long room, java.util.function.Consumer<Usage> usageSink, long minFreed) {
        int p = cutIndex(messages, 1);
        int n = messages.size();
        if (p >= n || !isPlainUserText(messages.get(p))) return false;
        long head = 0;
        for (int i = 0; i <= p; i++) head += TokenEstimate.tokens(messages.get(i).toString().length());
        long target = Math.min(config.compaction().keepTokens(), room - head - SUMMARY_TOKENS);
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
        if (k < 0) return false; // a single tool round: nothing to cut inside the turn
        if (suffix[p + 1] - suffix[k] < minFreed) return false; // a summary would not be smaller
        ArrayNode span = slice(messages, p, k); // the prompt gives the rounds their context
        String summary;
        try {
            summary = summarizeSpan(span, span.size(), "the steps already taken for the request in progress, their "
                    + "findings and what is left to do; the request itself stays verbatim", usageSink, null, null);
        } catch (RuntimeException e) {
            log.warn("Summary of the current turn failed: {}", e.getMessage());
            return false;
        }
        if (summary == null) return false;
        ObjectNode first = (ObjectNode) messages.get(k);
        ArrayNode blocks = mapper.createArrayNode();
        blocks.addObject().put("type", "text").put("text", TURN_SUMMARY_PREFIX + summary);
        JsonNode c = first.path("content");
        if (c.isTextual()) blocks.addObject().put("type", "text").put("text", c.asText());
        else if (c.isArray()) blocks.addAll((ArrayNode) c);
        first.set("content", blocks);
        for (int i = k - 1; i > p; i--) messages.remove(i);
        return true;
    }

    /** Blanks tool_result bodies older than the last {@code keepTurns} turns. @return number of blocks cleared. */
    int microcompact(ArrayNode messages, int keepTurns) {
        int cut = cutIndex(messages, keepTurns);
        int cleared = 0;
        for (int i = 0; i < cut; i++) {
            JsonNode content = messages.get(i).path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                if (!"tool_result".equals(block.path("type").asText())) continue;
                String text = block.path("content").isTextual() ? block.path("content").asText() : block.path("content").toString();
                if (text.length() > CLEARED.length() + 50) {
                    ((ObjectNode) block).put("content", CLEARED);
                    cleared++;
                }
            }
        }
        if (cleared > 0) stripThinking(messages, false);
        return cleared;
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

    private boolean summarize(ArrayNode messages, int keepTurns, java.util.function.Consumer<Usage> usageSink,
                              RequestPrefix prefix, ArrayNode original) {
        int cut = Math.max(cutIndex(messages, keepTurns), tokenCutIndex(messages, config.compaction().keepTokens()));
        if (cut <= 0 || onlySummary(messages, cut)) return false;
        String summary = summarizeSpan(messages, cut, null, usageSink, prefix, original);
        if (summary == null) return false;
        replaceSpan(messages, cut, summary);
        return true;
    }

    private boolean usable(RequestPrefix prefix) {
        return prefix != null && prefix.client() == model;
    }

    /**
     * Summary of the first {@code cut} messages. When an earlier summary starts the span the "update" prompt merges it
     * with the newer history. A conversation too long for the model is sent in a shorter form (see
     * {@link #render(ArrayNode, int)}), up to user text only.
     *
     * @param original when not null, the log the span is taken from (same indexes as {@code messages}): the log
     *                 before tool results were cleared, also what the provider has cached
     */
    private String summarizeSpan(ArrayNode messages, int cut, String focus, java.util.function.Consumer<Usage> usageSink,
                                 RequestPrefix prefix, ArrayNode original) {
        ArrayNode span = slice(original != null ? original : messages, 0, cut);
        String instruction = startsWithSummary(span) ? CompactionPrompt.update(focus) : CompactionPrompt.create(focus);
        long rejected = -1; // size in chars of the first request the provider refused as too long
        int first = 0;
        if (usable(prefix) && original != null) {
            try {
                String s = summaryOnPrefix(prefix, span, instruction, usageSink);
                if (s != null) return s;
            } catch (ModelException e) {
                if (!ModelException.isContextOverflow(e)) throw e;
                rejected = span.toString().length() + instruction.length();
                first = 1;
            }
        }
        // plain text requests, each shorter than the last: clip outputs, drop outputs, user text only, and cut the
        // oldest history down to 85/70/50/35% of the first refused size (an earlier summary is always kept whole)
        for (int attempt = first; attempt < SHRINK.length; attempt++) {
            int level = Math.min(attempt, MAX_SHRINK);
            String body = render(span, level);
            if (rejected > 0) {
                int cap = (int) (rejected * SHRINK[attempt]);
                if (body.length() > cap) body = shorten(span, level, cap);
                if (body == null) {
                    log.warn("The previous summary alone is over the size the model accepts: it is kept unchanged");
                    return null;
                }
            }
            String user = instruction + "\n\n<conversation>\n" + body + "</conversation>";
            try {
                return model.simpleComplete(CompactionPrompt.SYSTEM, user, SUMMARY_TOKENS, usageSink);
            } catch (ModelException e) {
                if (!ModelException.isContextOverflow(e)) throw e;
                if (rejected < 0) rejected = user.length();
            }
        }
        return null; // mostly fixed instructions and tool schemas: shortening the history cannot help
    }

    /**
     * {@code span} rendered within {@code cap} chars: an earlier summary that starts the span whole, then the newest
     * history that fits, starting on a line boundary. @return null when the earlier summary alone does not fit
     */
    String shorten(ArrayNode span, int level, int cap) {
        boolean prior = startsWithSummary(span);
        String head = prior ? render(slice(span, 0, 1), level) : "";
        String rest = render(prior ? slice(span, 1, span.size()) : span, level);
        int avail = cap - head.length();
        if (avail <= 0) return null;
        if (rest.length() <= avail) return head + rest;
        String tail = rest.substring(rest.length() - avail);
        int nl = tail.indexOf('\n');
        if (nl >= 0) tail = tail.substring(nl + 1); // no half line at the start
        return head + OMITTED + tail;
    }

    /** The agent's own request with the summary instruction as one more user turn; null if no text came back. */
    private String summaryOnPrefix(RequestPrefix prefix, ArrayNode span, String instruction,
                                   java.util.function.Consumer<Usage> usageSink) {
        ArrayNode req = mapper.createArrayNode();
        int n = span.size();
        boolean mergeIntoLast = n > 0 && "user".equals(span.get(n - 1).path("role").asText());
        for (int i = 0; i < n - (mergeIntoLast ? 1 : 0); i++) req.add(span.get(i));
        String ask = instruction + "\n\nDo not call any tool and do not continue the task: only write the summary of "
                + "the conversation above.";
        ObjectNode last = mergeIntoLast ? span.get(n - 1).deepCopy() : req.addObject().put("role", "user");
        if (mergeIntoLast) {
            JsonNode c = last.path("content");
            ArrayNode blocks = mapper.createArrayNode();
            if (c.isTextual()) blocks.addObject().put("type", "text").put("text", c.asText());
            else blocks.addAll((ArrayNode) c);
            last.set("content", blocks);
            req.add(last);
        } else {
            last.putArray("content");
        }
        ((ArrayNode) last.get("content")).addObject().put("type", "text").put("text", ask);
        org.kendar.llm.harness.llm.ModelResponse resp = model.complete(new org.kendar.llm.harness.llm.ModelRequest(
                prefix.pinned(), prefix.extra(), req, prefix.tools(), SUMMARY_TOKENS, null,
                prefix.disableParallelToolUse()));
        if (usageSink != null && resp.usage() != null) usageSink.accept(resp.usage());
        StringBuilder text = new StringBuilder();
        for (JsonNode b : resp.content()) {
            if ("text".equals(b.path("type").asText())) text.append(b.path("text").asText());
        }
        return text.toString().isBlank() ? null : text.toString().strip();
    }

    private static boolean startsWithSummary(ArrayNode span) {
        if (span.isEmpty()) return false;
        JsonNode c = span.get(0).path("content");
        String text = c.isTextual() ? c.asText() : c.path(0).path("text").asText("");
        return text.startsWith(SUMMARY_PREFIX);
    }

    /**
     * Start of the newest run of whole turns that fits in {@code keepTokens}: what stays verbatim beside the
     * summary. The latest turn is always kept, even when it alone is larger.
     */
    public int tokenCutIndex(ArrayNode messages, int keepTokens) {
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

    private void replaceSpan(ArrayNode messages, int cut, String summary) {
        ArrayNode tail = slice(messages, cut, messages.size());
        messages.removeAll();
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.putArray("content").addObject().put("type", "text").put("text", SUMMARY_PREFIX + summary);
        messages.addAll(tail);
        stripThinking(messages, false);
    }

    /**
     * Index of the plain-user-text message that starts the {@code keepTurns}-th most recent turn, so a cut there
     * never separates a tool_use from its tool_result. 0 when there are not enough turns to cut.
     */
    static int cutIndex(ArrayNode messages, int keepTurns) {
        int turns = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isPlainUserText(messages.get(i)) && ++turns == Math.max(1, keepTurns)) {
                return i;
            }
        }
        return 0;
    }

    static boolean isPlainUserText(JsonNode msg) {
        if (!"user".equals(msg.path("role").asText())) return false;
        JsonNode c = msg.path("content");
        if (c.isTextual()) return true;
        if (!c.isArray() || c.isEmpty()) return false;
        for (JsonNode b : c) {
            if (!"text".equals(b.path("type").asText())) return false;
        }
        return true;
    }

    private ArrayNode slice(ArrayNode src, int from, int to) {
        ArrayNode out = mapper.createArrayNode();
        for (int i = from; i < to; i++) out.add(src.get(i));
        return out;
    }

    /** Plain-text transcript used for summarization and for the old-vs-new diff view. */
    String render(ArrayNode messages) {
        return render(messages, 0);
    }

    /**
     * @param level 0 whole exchanges (long outputs clipped); 1 shorter outputs and inputs; 2 no tool outputs; 3 user
     *              text only
     */
    String render(ArrayNode messages, int level) {
        int inputMax = level == 0 ? 400 : level == 1 ? 200 : 80;
        int resultMax = level == 0 ? 600 : 200;
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : messages) {
            if (level >= 3 && !isPlainUserText(m)) continue;
            sb.append(m.path("role").asText().toUpperCase()).append(": ");
            JsonNode c = m.path("content");
            if (c.isTextual()) {
                sb.append(c.asText()).append('\n');
                continue;
            }
            for (JsonNode b : c) {
                switch (b.path("type").asText()) {
                    case "text" -> sb.append(b.path("text").asText());
                    case "tool_use" -> sb.append("[tool_use ").append(b.path("name").asText()).append(' ')
                            .append(cut(b.path("input").toString(), inputMax)).append(']');
                    case "tool_result" -> {
                        JsonNode r = b.path("content");
                        if (level >= 2) sb.append("[tool_result omitted]");
                        else sb.append("[tool_result ").append(clip(r.isTextual() ? r.asText() : r.toString(), resultMax))
                                .append(']');
                    }
                    default -> { /* thinking etc. are not worth summarizing */ }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Head and tail of a long text. */
    private static String clip(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max / 2) + " … " + s.substring(s.length() - max / 2);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private ObjectNode obj(String k, Object v) {
        return obj(k, v, null, null);
    }

    private ObjectNode obj(String k1, Object v1, String k2, Object v2) {
        ObjectNode n = mapper.createObjectNode();
        n.putPOJO(k1, v1);
        if (k2 != null) n.putPOJO(k2, v2);
        return n;
    }
}

package org.kendar.llm.harness.context.compaction;

import org.kendar.llm.harness.context.ClearedResult;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** Manual compaction leaves a conversation alone when what it would summarize is smaller than this. */
    static final int MIN_SUMMARY_SOURCE_TOKENS = 500;
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

    /**
     * True when the whole request (messages plus the fixed part) is over {@code budget}: by the estimate corrected
     * with {@code ratio}, or as the provider measured it.
     */
    private boolean over(ArrayNode messages, Usage lastUsage, RequestPrefix prefix, long budget, double ratio) {
        long est = TokenEstimator.estimateTokens(messages, null) + TokenEstimator.fixedTokens(prefix);
        return est > TokenEstimator.scaled(budget, ratio) || (lastUsage != null && lastUsage.contextTokens() > budget);
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
        if (config.compaction().deferOnThinkingOverflow() && CutPlanner.currentTurnHasThinking(messages)) return false;
        boolean changed = compactTo(chatId, messages, lastUsage, sink, cancelled, usageSink, allowRewrite,
                config.compaction().ceiling(config.contextBudgetTokens()), prefix, ratio);
        if (changed) CutPlanner.stripThinking(messages, true);
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
        if (!force && config.compaction().deferOnThinkingOverflow() && CutPlanner.currentTurnHasThinking(messages)) return false;
        long ceiling = TokenEstimator.scaled(budget, ratio);
        long fixed = TokenEstimator.fixedTokens(prefix);
        if (!force && !over(messages, lastUsage, prefix, budget, ratio)) return false;
        ArrayNode originalCopy = original != null && original.size() == messages.size()
                ? slice(original, 0, inherited[0]) : null;
        ArrayNode copy = slice(messages, 0, inherited[0]);
        ArrayNode own = slice(messages, inherited[0], messages.size());
        boolean changed = false;
        long room = ceiling - fixed - TokenEstimator.estimateTokens(copy, null);
        boolean ownDone = room > 0;
        if (room > 0) {
            try {
                changed = force ? compactOverflow(own, room, usageSink).changed()
                        : compactTo(chatId, own, null, sink, () -> false, usageSink, false, room, null, 1.0);
            } catch (RuntimeException e) { // boundary: a failing summary call degrades compaction, the run goes on
                log.warn("Compaction of the agent's own messages failed: {}", e.getMessage());
            }
        }
        boolean copyIsSummary = copy.size() == 2 && SummaryBuilder.startsWithSummary(copy);
        if (TokenEstimator.estimateTokens(copy, null) + TokenEstimator.estimateTokens(own, null) + fixed > ceiling
                || (force && !changed && !copyIsSummary)) {
            try {
                String summary = summarizeSpan(copy, copy.size(), null, usageSink, null, originalCopy);
                if (summary == null) throw new IllegalStateException("Nothing to compact yet");
                copy = mapper.createArrayNode();
                copy.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                        .put("text", SummaryBuilder.SUMMARY_PREFIX + summary);
                copy.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text")
                        .put("text", "Understood.");
                inherited[0] = 2;
                metrics.compaction(2);
                sink.emit("compaction", obj("tier", 2, "inherited", true));
                changed = true;
            } catch (RuntimeException e) { // boundary: a failing summary call degrades compaction, the run goes on
                log.warn("Compaction of the inherited context failed: {}", e.getMessage());
            }
        }
        // the copy alone left no room for the agent's own messages: they get their turn now that it is a summary
        long left = ceiling - fixed - TokenEstimator.estimateTokens(copy, null);
        if (!ownDone && left > 0 && TokenEstimator.estimateTokens(own, null) > left) {
            try {
                changed |= force ? compactOverflow(own, left, usageSink).changed()
                        : compactTo(chatId, own, null, sink, () -> false, usageSink, false, left, null, 1.0);
            } catch (RuntimeException e) { // boundary: a failing summary call degrades compaction, the run goes on
                log.warn("Compaction of the agent's own messages failed: {}", e.getMessage());
            }
        }
        if (changed) {
            messages.removeAll();
            messages.addAll(copy);
            messages.addAll(own);
            // the copy's thinking signatures were bound to a history that is now rewritten
            CutPlanner.stripThinking(messages, true);
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
        long room = TokenEstimator.scaled(budget, ratio) - TokenEstimator.fixedTokens(prefix);
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
        if (TokenEstimator.estimateTokens(messages, null) <= room && !(measuredOver && cleared == 0)) return changed;

        // tier 2
        boolean summarized = false;
        try {
            if (summarize(messages, config.keepRecentTurns(), usageSink, prefix, original)) {
                changed = true;
                summarized = true;
                metrics.compaction(2);
                sink.emit("compaction", obj("tier", 2, "estimatedTokens", TokenEstimator.estimateTokens(messages, null)));
            }
        } catch (RuntimeException e) { // boundary: a failing summary call degrades compaction, the run goes on
            log.warn("Tier-2 compaction failed: {}", e.getMessage());
            sink.emit("warning", obj("message", "Context summarization failed: " + e.getMessage()));
        }
        long size = TokenEstimator.estimateTokens(messages, null);
        // still too large (one long turn has no turn boundary to cut at): summarize its older tool rounds
        if (size > room || (measuredOver && !changed)) {
            if (cutInsideTurn(messages, room, usageSink, SummaryBuilder.SUMMARY_TOKENS)) {
                changed = true;
                metrics.compaction(2);
                size = TokenEstimator.estimateTokens(messages, null);
                sink.emit("compaction", obj("tier", 2, "inTurn", true));
            }
        }
        if (size <= room) return changed;

        // tier 3: user-approved aggressive rewrite
        if (!allowRewrite) return changed;
        Long declinedAt = declined.get(chatId);
        if (declinedAt != null && size < declinedAt * 1.2) return changed;
        int cut = CutPlanner.cutIndex(messages, 1);
        if (cut <= 0) return changed;
        // only an earlier summary before the current turn: a rewrite helps when the rest would fit without it, not
        // when the turn in progress is what is too large
        if (CutPlanner.onlySummary(messages, cut)
                && size - TokenEstimate.tokens(messages.get(0).toString().length()) > room) return changed;
        try {
            // without a tier-2 rewrite the indexes still match the uncleared log
            String proposed = summarizeSpan(messages, cut, null, usageSink, null, summarized ? null : original);
            if (proposed == null) return changed;
            ArrayNode preview = slice(messages, 0, cut);
            ApprovalService.ApprovalDecision d = approvals.await(chatId, "context",
                    "Context is over budget — approve a rewrite", SummaryBuilder.render(preview), proposed,
                    a -> sink.emit("approval_request", mapper.valueToTree(a)), cancelled);
            if (d.approved()) {
                String text = d.edited() != null && !d.edited().isBlank() ? d.edited() : proposed;
                replaceSpan(messages, cut, text);
                declined.remove(chatId);
                changed = true;
                metrics.compaction(3);
                metrics.approval("context", true);
                sink.emit("compaction", obj("tier", 3, "estimatedTokens", TokenEstimator.estimateTokens(messages, null)));
            } else {
                metrics.approval("context", false);
                declined.put(chatId, size);
            }
        } catch (RuntimeException e) { // boundary: a failing summary call is reported as a warning
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
        long before = TokenEstimator.estimateTokens(messages, null);
        // summarized from the log as it was before the tool results are cleared (same indexes)
        if (original == null || original.size() != messages.size()) original = messages.deepCopy();
        int cleared = microcompact(messages, 1);
        boolean summarized = false;
        int cut = CutPlanner.cutIndex(messages, 1);
        // a summary is never smaller than its template: below this size the model call (slow, and a weak model then
        // summarizes the instructions instead of the conversation) buys nothing
        long spanTokens = cut > 0 ? TokenEstimator.estimateTokens(slice(original, 0, cut), null) : 0;
        boolean tooSmall = cut > 0 && spanTokens < MIN_SUMMARY_SOURCE_TOKENS;
        if (cut > 0 && !tooSmall && !CutPlanner.onlySummary(messages, cut)) {
            String summary = summarizeSpan(messages, cut, focus, usageSink, prefix, original);
            if (summary != null) { // null: even the shortest form was rejected, nothing to compact yet
                replaceSpan(messages, cut, summary);
                summarized = true;
                metrics.compaction(2);
            }
        }
        if (cleared > 0 || summarized) CutPlanner.stripThinking(messages, true);
        String note = tooSmall && !summarized && cleared == 0
                ? "Nothing worth compacting: only about " + spanTokens + " tokens precede the latest turn" : null;
        return new CompactResult(cleared > 0 || summarized, before, TokenEstimator.estimateTokens(messages, null), cleared, note);
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
        long before = TokenEstimator.estimateTokens(messages, null);
        ArrayNode original = messages.deepCopy();
        int cleared = microcompact(messages, 1);
        boolean summarized = false;
        int cut = CutPlanner.cutIndex(messages, 1);
        if (cut > 0 && !CutPlanner.onlySummary(messages, cut)) {
            try {
                String summary = summarizeSpan(messages, cut, null, usageSink, null, original);
                if (summary != null) {
                    replaceSpan(messages, cut, summary);
                    summarized = true;
                    metrics.compaction(2);
                }
            } catch (RuntimeException e) { // boundary: a failing summary call degrades compaction, the run goes on
                log.warn("Summary of the earlier turns failed: {}", e.getMessage());
            }
        }
        if (!summarized || TokenEstimator.estimateTokens(messages, null) > room) {
            if (cutInsideTurn(messages, room, usageSink)) {
                summarized = true;
                metrics.compaction(2);
            }
        }
        boolean changed = cleared > 0 || summarized;
        if (changed) CutPlanner.stripThinking(messages, true);
        return new CompactResult(changed, before, TokenEstimator.estimateTokens(messages, null), cleared);
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
        CutPlanner.InTurnCut plan = CutPlanner.inTurnCut(messages, room, config.compaction().keepTokens(),
                SummaryBuilder.SUMMARY_TOKENS, minFreed);
        if (plan == null) return false;
        int p = plan.prompt();
        int k = plan.cut();
        ArrayNode span = slice(messages, p, k); // the prompt gives the rounds their context
        String summary;
        try {
            summary = summarizeSpan(span, span.size(), "the steps already taken for the request in progress, their "
                    + "findings and what is left to do; the request itself stays verbatim", usageSink, null, null);
        } catch (RuntimeException e) { // boundary: a failing summary call degrades compaction, the run goes on
            log.warn("Summary of the current turn failed: {}", e.getMessage());
            return false;
        }
        if (summary == null) return false;
        ObjectNode first = (ObjectNode) messages.get(k);
        ArrayNode blocks = mapper.createArrayNode();
        blocks.addObject().put("type", "text").put("text", SummaryBuilder.TURN_SUMMARY_PREFIX + summary);
        JsonNode c = first.path("content");
        if (c.isTextual()) blocks.addObject().put("type", "text").put("text", c.asText());
        else if (c.isArray()) blocks.addAll((ArrayNode) c);
        first.set("content", blocks);
        for (int i = k - 1; i > p; i--) messages.remove(i);
        return true;
    }

    /** Blanks tool_result bodies older than the last {@code keepTurns} turns. @return number of blocks cleared. */
    int microcompact(ArrayNode messages, int keepTurns) {
        int cut = CutPlanner.cutIndex(messages, keepTurns);
        int cleared = 0;
        for (int i = 0; i < cut; i++) {
            JsonNode content = messages.get(i).path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                if (!"tool_result".equals(block.path("type").asText())) continue;
                String text = block.path("content").isTextual() ? block.path("content").asText() : block.path("content").toString();
                if (text.length() > ClearedResult.TEXT.length() + 50) {
                    ((ObjectNode) block).put("content", ClearedResult.TEXT);
                    cleared++;
                }
            }
        }
        if (cleared > 0) CutPlanner.stripThinking(messages, false);
        return cleared;
    }


    private boolean summarize(ArrayNode messages, int keepTurns, java.util.function.Consumer<Usage> usageSink,
                              RequestPrefix prefix, ArrayNode original) {
        int cut = Math.max(CutPlanner.cutIndex(messages, keepTurns), CutPlanner.tokenCutIndex(messages, config.compaction().keepTokens()));
        if (cut <= 0 || CutPlanner.onlySummary(messages, cut)) return false;
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
     * {@link #SummaryBuilder.render(ArrayNode, int)}), up to user text only.
     *
     * @param original when not null, the log the span is taken from (same indexes as {@code messages}): the log
     *                 before tool results were cleared, also what the provider has cached
     */
    private String summarizeSpan(ArrayNode messages, int cut, String focus, java.util.function.Consumer<Usage> usageSink,
                                 RequestPrefix prefix, ArrayNode original) {
        ArrayNode span = slice(original != null ? original : messages, 0, cut);
        String instruction = SummaryBuilder.startsWithSummary(span) ? CompactionPrompt.update(focus) : CompactionPrompt.create(focus);
        long rejected = -1; // size in chars of the first request the provider refused as too long
        int first = 0;
        if (usable(prefix) && original != null) {
            try {
                String s = summaryOnPrefix(prefix, span, instruction, usageSink);
                if (s != null) return SummaryBuilder.withKeptSections(s, span);
            } catch (ModelException e) {
                if (!ModelException.isContextOverflow(e)) throw e;
                rejected = span.toString().length() + instruction.length();
                first = 1;
            }
        }
        // plain text requests, each shorter than the last: clip outputs, drop outputs, user text only, and cut the
        // oldest history down to 85/70/50/35% of the first refused size (an earlier summary is always kept whole)
        for (int attempt = first; attempt < SHRINK.length; attempt++) {
            int level = Math.min(attempt, SummaryBuilder.MAX_SHRINK);
            String body = SummaryBuilder.render(span, level);
            if (rejected > 0) {
                int cap = (int) (rejected * SHRINK[attempt]);
                if (body.length() > cap) body = SummaryBuilder.shorten(span, level, cap);
                if (body == null) {
                    log.warn("The previous summary alone is over the size the model accepts: it is kept unchanged");
                    return null;
                }
            }
            String user = instruction + "\n\n<conversation>\n" + body + "</conversation>";
            try {
                String s = model.simpleComplete(CompactionPrompt.SYSTEM, user, SummaryBuilder.SUMMARY_TOKENS, usageSink);
                return s == null ? null : SummaryBuilder.withKeptSections(s, span);
            } catch (ModelException e) {
                if (!ModelException.isContextOverflow(e)) throw e;
                if (rejected < 0) rejected = user.length();
            }
        }
        return null; // mostly fixed instructions and tool schemas: shortening the history cannot help
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
                prefix.pinned(), prefix.extra(), req, prefix.tools(), SummaryBuilder.SUMMARY_TOKENS, null,
                prefix.disableParallelToolUse(), null, null));
        if (usageSink != null && resp.usage() != null) usageSink.accept(resp.usage());
        StringBuilder text = new StringBuilder();
        for (JsonNode b : resp.content()) {
            if ("text".equals(b.path("type").asText())) text.append(b.path("text").asText());
        }
        return text.toString().isBlank() ? null : text.toString().strip();
    }



    private void replaceSpan(ArrayNode messages, int cut, String summary) {
        ArrayNode tail = slice(messages, cut, messages.size());
        messages.removeAll();
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.putArray("content").addObject().put("type", "text").put("text", SummaryBuilder.SUMMARY_PREFIX + summary);
        messages.addAll(tail);
        CutPlanner.stripThinking(messages, false);
    }

    private ArrayNode slice(ArrayNode src, int from, int to) {
        ArrayNode out = mapper.createArrayNode();
        for (int i = from; i < to; i++) out.add(src.get(i));
        return out;
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

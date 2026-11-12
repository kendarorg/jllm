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
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.ApprovalService;
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
        long chars = messages.toString().length() / 4;
        return lastUsage == null ? chars : Math.max(chars, lastUsage.contextTokens());
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
        if (!config.compaction().auto()) return false;
        // rewriting history invalidates the signed thinking blocks of the turn in progress: optionally wait for it to end
        if (config.compaction().deferOnThinkingOverflow() && currentTurnHasThinking(messages)) return false;
        boolean changed = compactTo(chatId, messages, lastUsage, sink, cancelled, usageSink, allowRewrite,
                config.compaction().ceiling(config.contextBudgetTokens()), prefix);
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
        if (!config.compaction().auto() || inherited[0] <= 0) {
            return compactIfNeeded(chatId, messages, lastUsage, sink, () -> false, usageSink, false, prefix);
        }
        long ceiling = config.compaction().ceiling(config.contextBudgetTokens());
        if (estimateTokens(messages, lastUsage) <= ceiling) return false;
        ArrayNode copy = slice(messages, 0, inherited[0]);
        ArrayNode own = slice(messages, inherited[0], messages.size());
        boolean changed = false;
        long room = ceiling - estimateTokens(copy, null);
        if (room > 0) changed = compactTo(chatId, own, null, sink, () -> false, usageSink, false, room, null);
        if (estimateTokens(copy, null) + estimateTokens(own, null) > ceiling) {
            try {
                String summary = summarizeSpan(copy, copy.size(), null, usageSink, null, null);
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
        if (changed) {
            messages.removeAll();
            messages.addAll(copy);
            messages.addAll(own);
        }
        return changed;
    }

    private boolean compactTo(String chatId, ArrayNode messages, Usage lastUsage, EventSink sink,
                              BooleanSupplier cancelled, java.util.function.Consumer<Usage> usageSink,
                              boolean allowRewrite, long budget, RequestPrefix prefix) {
        long before = estimateTokens(messages, lastUsage);
        if (before <= budget) return false;
        // the unclipped log is what the provider has cached: the summary request is built from it
        ArrayNode original = usable(prefix) ? messages.deepCopy() : null;

        boolean changed = false;
        // tier 1
        int cleared = microcompact(messages, config.keepRecentTurns());
        if (cleared > 0) {
            changed = true;
            metrics.compaction(1);
            sink.emit("compaction", obj("tier", 1, "cleared", cleared));
        }
        // after tier 1 the old usage figure is stale: rely on the size estimate
        if (estimateTokens(messages, null) <= budget) return changed;

        // tier 2
        try {
            if (summarize(messages, config.keepRecentTurns(), usageSink, prefix, original)) {
                changed = true;
                metrics.compaction(2);
                sink.emit("compaction", obj("tier", 2, "estimatedTokens", estimateTokens(messages, null)));
            }
        } catch (RuntimeException e) {
            log.warn("Tier-2 compaction failed: {}", e.getMessage());
            sink.emit("warning", obj("message", "Context summarization failed: " + e.getMessage()));
        }
        long size = estimateTokens(messages, null);
        if (size <= budget) return changed;

        // tier 3: user-approved aggressive rewrite
        if (!allowRewrite) return changed;
        Long declinedAt = declined.get(chatId);
        if (declinedAt != null && size < declinedAt * 1.2) return changed;
        int cut = cutIndex(messages, 1);
        if (cut <= 0) return changed;
        try {
            String proposed = summarizeSpan(messages, cut, null, usageSink, null, null);
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
        long before = estimateTokens(messages, null);
        ArrayNode original = usable(prefix) ? messages.deepCopy() : null;
        int cleared = microcompact(messages, 1);
        boolean summarized = false;
        int cut = cutIndex(messages, 1);
        if (cut > 0) {
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
    static void stripThinking(ArrayNode messages, boolean includeCurrent) {
        int cut = includeCurrent ? messages.size() : cutIndex(messages, 1);
        for (int i = 0; i < cut; i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText()) || !m.path("content").isArray()) continue;
            ArrayNode content = (ArrayNode) m.path("content");
            for (int j = content.size() - 1; j >= 0 && content.size() > 1; j--) {
                if (isThinkingBlock(content.get(j))) content.remove(j);
            }
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
        if (cut <= 0) return false;
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
     */
    private String summarizeSpan(ArrayNode messages, int cut, String focus, java.util.function.Consumer<Usage> usageSink,
                                 RequestPrefix prefix, ArrayNode original) {
        ArrayNode span = slice(messages, 0, cut);
        String instruction = startsWithSummary(span) ? CompactionPrompt.update(focus) : CompactionPrompt.create(focus);
        long rejected = -1; // size in chars of the first request the provider refused as too long
        int first = 0;
        if (usable(prefix) && original != null) {
            ArrayNode cached = slice(original, 0, cut);
            try {
                String s = summaryOnPrefix(prefix, cached, instruction, usageSink);
                if (s != null) return s;
            } catch (ModelException e) {
                if (!ModelException.isContextOverflow(e)) throw e;
                rejected = cached.toString().length() + instruction.length();
                first = 1;
            }
        }
        // plain text requests, each shorter than the last: clip outputs, drop outputs, user text only, and cut the
        // oldest history down to 85/70/50/35% of the first refused size
        for (int attempt = first; attempt < SHRINK.length; attempt++) {
            String body = render(span, Math.min(attempt, MAX_SHRINK));
            if (rejected > 0) {
                int cap = (int) (rejected * SHRINK[attempt]);
                if (body.length() > cap) body = "[earlier history omitted]\n" + body.substring(body.length() - cap);
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
            size += messages.get(i).toString().length() / 4;
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

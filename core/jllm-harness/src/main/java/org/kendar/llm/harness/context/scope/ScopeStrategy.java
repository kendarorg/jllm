package org.kendar.llm.harness.context.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.*;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Keeps the whole request inside the context window, cheapest step first:
 * <ol>
 *   <li>offload large tool results to disk, leaving a head and a tail in the context</li>
 *   <li>clear the bodies of old tool results</li>
 *   <li>summarize the older turns</li>
 * </ol>
 * The first user message of the chat is never compacted, and the todo list lives outside the transcript: it is added
 * to every request and cannot be lost. The request is measured as a whole (system prompt, tool schemas, messages)
 * minus a reserve for the answer. A provider overflow gets one forced, more aggressive pass.
 */
public class ScopeStrategy implements ContextStrategy {

    private static final Logger log = LoggerFactory.getLogger(ScopeStrategy.class);

    static final String OFFLOADED = "[Output offloaded to ";
    static final String CLEARED = "[tool result cleared to save context]";
    static final String ACK = "Understood.";
    private static final String SUMMARY_PREFIX = "[Summary of the earlier conversation]";
    /** Rough size of the first line of an offload pointer (marker, path, size). */
    private static final int POINTER_HEADER_CHARS = 150;
    /** Outside a forced pass, the tool rounds of the turn in progress are summarized only when they hold this much. */
    private static final long MIN_TURN_CUT_TOKENS = 3000;

    /** Where offloaded tool results go: a place the agent's file tools can read. */
    @FunctionalInterface
    public interface OffloadSink {

        /** Stores {@code text} as {@code name}. @return the path shown to the model */
        String store(String chatId, String agentKey, String name, String text) throws IOException;

        /**
         * Files on the host.
         *
         * @param dir     {@code (chatId, agentKey)} to the agent's scratch directory
         * @param display how a path is shown to the model
         */
        static OffloadSink host(BiFunction<String, String, Path> dir, Function<Path, String> display) {
            return (chatId, agentKey, name, text) -> {
                Path folder = dir.apply(chatId, agentKey).resolve("offload");
                Files.createDirectories(folder);
                Path file = folder.resolve(name);
                Files.writeString(file, text, StandardCharsets.UTF_8);
                return display.apply(file);
            };
        }
    }

    /**
     * @param offloadChars         tool results longer than this are offloaded when the request is over budget
     * @param overflowOffloadChars the same threshold on the forced pass after a provider overflow
     * @param previewChars         head and tail kept in the context for an offloaded result
     * @param safetyPercent        share of the window kept free as a safety margin
     * @param reserveCap           room (tokens) reserved for the answer when it should be less than {@code maxTokens};
     *                             0 = {@code maxTokens}. Never more than a quarter of the window
     */
    public record Options(int offloadChars, int overflowOffloadChars, int previewChars, int safetyPercent,
                          int reserveCap) {

        public static Options defaults() {
            return new Options(20_000, 2_000, 1_500, 2, 0);
        }
    }

    private final HarnessConfig config;
    private final ContextManager manager;
    private final ObjectMapper mapper;
    private final TodoStore todos;
    private final Options options;
    private final OffloadSink offload;
    private final List<HarnessTool> tools;
    private final TokenEstimate tokens = new TokenEstimate();

    /**
     * @param offloadDir {@code (chatId, agentKey)} to a directory the agent's file tools can read
     * @param display    how a path is shown to the model (container paths differ from host paths)
     */
    public ScopeStrategy(HarnessConfig config, ContextManager manager, ObjectMapper mapper, TodoStore todos,
                         Options options, BiFunction<String, String, Path> offloadDir, Function<Path, String> display) {
        this(config, manager, mapper, todos, options, OffloadSink.host(offloadDir, display));
    }

    public ScopeStrategy(HarnessConfig config, ContextManager manager, ObjectMapper mapper, TodoStore todos,
                         Options options, OffloadSink offload) {
        this.config = config;
        this.manager = manager;
        this.mapper = mapper;
        this.todos = todos;
        this.options = options;
        this.offload = offload;
        this.tools = TodoTools.create(todos, mapper);
    }

    public static String systemAddendum() {
        return """
            # Task list
            
            For work with 3 or more steps, keep a task list with todo_write (send the complete list \
            each time, at most one item in_progress, mark items completed as soon as they are done). The \
            current list is shown to you with every request; todo_read returns it. Large tool results may be \
            offloaded to a file: the result then names the file, read it with read_file when you need more.""";
    }

    @Override
    public ContextStrategyKind kind() {
        return ContextStrategyKind.SCOPE;
    }

    @Override
    public List<HarnessTool> tools() {
        return tools;
    }

    @Override
    public ContextView prepare(ContextTurn t) {
        boolean changed = false;
        if (t.lastUsage() != null) tokens.measured(t.chatId(), t.agentKey(), t.lastUsage().contextTokens());
        // rewriting history voids the signed thinking blocks of the turn in progress: optionally wait for it to end
        boolean defer = config.compaction().deferOnThinkingOverflow() && ContextManager.currentTurnHasThinking(t.log());
        if (config.compaction().auto() && !defer) {
            long real = budget(t.prefix());
            // the budget in the unit of the uncorrected estimate
            long budget = scaledBudget(t);
            long est = estimate(t.log(), t.prefix(), null);
            long measured = t.lastUsage() == null ? 0 : t.lastUsage().contextTokens();
            // measured over the budget whatever the estimate says: shrink in proportion
            if (measured > real) budget = Math.min(budget, (long) (est * ((double) real / measured)) - 1);
            if (est > budget) {
                changed = shrink(t, budget, options.offloadChars(), config.keepRecentTurns(), false);
            }
        }
        ArrayNode view = withTodos(t);
        tokens.sent(t.chatId(), t.agentKey(), view.toString().length() + ContextManager.fixedChars(t.prefix()));
        return new ContextView(view, changed, changed);
    }

    @Override
    public ContextView onOverflow(ContextTurn t) {
        // the estimate was too low: trust it less from now on
        tokens.overflowed(t.chatId(), t.agentKey());
        boolean changed = shrink(t, 0, options.overflowOffloadChars(), 1, true);
        return changed ? new ContextView(withTodos(t), true, true) : null;
    }

    @Override
    public CompactResult compactNow(ContextTurn t, String focus) {
        long before = estimate(t.log(), null, null);
        if (inheritsCopy(t)) {
            boolean changed = manager.compactInherited(t.chatId(), t.log(), t.inherited(), null, t.sink(),
                    t.usageSink(), t.prefix(), true, budget(t.prefix()), tokens.ratio(t.chatId(), t.agentKey()), null);
            return new CompactResult(changed, before, estimate(t.log(), null, null), 0);
        }
        ArrayNode beforeClear = t.log().deepCopy();
        int cleared = clearOld(t.log(), 1);
        boolean summarized = summarize(t, focus, beforeClear);
        if (cleared > 0 || summarized) ContextManager.stripThinking(t.log(), true);
        return new CompactResult(cleared > 0 || summarized, before, estimate(t.log(), null, null), cleared);
    }

    @Override
    public long contextTokens(String chatId, String agentKey, ArrayNode log) {
        return Math.round(estimate(log, null, null) * tokens.ratio(chatId, agentKey));
    }

    @Override
    public void release(String chatId, String agentKey) {
        tokens.release(chatId, agentKey);
    }

    @Override
    public void forget(String chatId) {
        tokens.forget(chatId);
    }

    /** A sub-agent that started from a copy of its parent's context: compaction must keep the copy boundary. */
    private static boolean inheritsCopy(ContextTurn t) {
        return !t.main() && t.inherited()[0] > 0;
    }

    /** {@link #budget} in the unit of the uncorrected estimate. */
    private long scaledBudget(ContextTurn t) {
        return (long) (budget(t.prefix()) / tokens.ratio(t.chatId(), t.agentKey()));
    }

    // ---- budget -------------------------------------------------------------------------------------------------

    /**
     * Window minus the answer reserve and the safety margin. The fixed part of the request (system prompt, tool
     * schemas) is counted once, on the usage side: see {@link #estimate}.
     */
    long budget(RequestPrefix prefix) {
        long window = config.contextBudgetTokens();
        long answer = options.reserveCap() > 0 ? Math.min(options.reserveCap(), config.maxTokens())
                : config.maxTokens();
        // the provider refuses a request whose input plus max_tokens is over the window
        long reserve = Math.min(answer, window / 4);
        long safety = window * options.safetyPercent() / 100;
        return window - reserve - safety;
    }

    /** The whole request: messages plus the fixed part, or the measured usage (which includes it) when larger. */
    static long estimate(ArrayNode messages, RequestPrefix prefix, org.kendar.llm.harness.llm.Usage last) {
        long chars = TokenEstimate.tokens(messages.toString().length()) + ContextManager.fixedTokens(prefix);
        return last == null ? chars : Math.max(chars, last.contextTokens());
    }

    // ---- the trim order -----------------------------------------------------------------------------------------

    private boolean shrink(ContextTurn t, long budget, int offloadChars, int keepTurns, boolean force) {
        boolean changed = trim(t, budget, offloadChars, keepTurns, force);
        // earlier content was rewritten: the thinking signatures bound to it are void
        if (changed) ContextManager.stripThinking(t.log(), true);
        return changed;
    }

    private boolean trim(ContextTurn t, long budget, int offloadChars, int keepTurns, boolean force) {
        ArrayNode msgs = t.log();
        boolean changed = false;
        // 1. offload: first outside the latest turn, then everywhere
        changed |= offload(t, offloadChars, latestTurn(msgs));
        if (!force && estimate(msgs, t.prefix(), null) <= budget) return changed;
        changed |= offload(t, offloadChars, msgs.size());
        if (!force && estimate(msgs, t.prefix(), null) <= budget) return changed;
        // 2. clear old tool results (the summary is made from the log as it was before)
        ArrayNode beforeClear = msgs.deepCopy();
        changed |= clearOld(msgs, keepTurns) > 0;
        if (!force && estimate(msgs, t.prefix(), null) <= budget) return changed;
        // 3. summarize the older turns; a sub-agent's inherited copy keeps its boundary, and its own task stays
        try {
            if (inheritsCopy(t)) {
                changed |= manager.compactInherited(t.chatId(), msgs, t.inherited(), null, t.sink(), t.usageSink(),
                        t.prefix(), true, budget(t.prefix()), tokens.ratio(t.chatId(), t.agentKey()), beforeClear);
            } else {
                changed |= summarize(t, null, beforeClear);
            }
        } catch (RuntimeException e) {
            log.warn("Summarization failed: {}", e.getMessage());
            t.sink().emit("warning", mapper.createObjectNode().put("message",
                    "Context summarization failed: " + e.getMessage()));
        }
        // 4. one long turn has no turn boundary to cut at: summarize its older tool rounds (the forced pass of an
        // inherited copy did it already)
        if (!inheritsCopy(t) && (force || estimate(msgs, t.prefix(), null) > budget)) {
            long room = (force ? scaledBudget(t) : budget) - ContextManager.fixedTokens(t.prefix());
            changed |= manager.cutInsideTurn(msgs, room, t.usageSink(), force ? 0 : MIN_TURN_CUT_TOKENS);
        }
        if (!force && estimate(msgs, t.prefix(), null) > budget) {
            t.sink().emit("warning", mapper.createObjectNode().put("message",
                    "The request is still over the context budget after compaction"));
        }
        if (changed) {
            t.sink().emit("compaction", mapper.createObjectNode().put("strategy", "scope")
                    .put("estimatedTokens", estimate(msgs, t.prefix(), null)));
        }
        return changed;
    }

    /** Index of the message that starts the latest turn (the last plain user text), 0 when there is none. */
    private static int latestTurn(ArrayNode msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) if (isPlainUserText(msgs.get(i))) return i;
        return 0;
    }

    private boolean offload(ContextTurn t, int threshold, int upTo) {
        boolean changed = false;
        // the preview follows the threshold, or a result just over a low threshold would grow
        int preview = Math.min(options.previewChars(), threshold / 4);
        for (int i = 0; i < upTo && i < t.log().size(); i++) {
            JsonNode content = t.log().get(i).path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                if (!"tool_result".equals(block.path("type").asText()) || !block.path("content").isTextual()) continue;
                String text = block.path("content").asText();
                if (text.length() <= threshold || text.startsWith(OFFLOADED)) continue;
                // only when it at least halves the result: nothing is written otherwise
                int kept = 2 * Math.min(preview, text.length() / 2) + POINTER_HEADER_CHARS;
                if (kept > text.length() / 2) continue;
                try {
                    String id = block.path("tool_use_id").asText("result").replaceAll("[^A-Za-z0-9._-]", "_");
                    String path = offload.store(t.chatId(), t.agentKey(), id + ".txt", text);
                    ((ObjectNode) block).put("content", pointer(path, text, preview));
                    changed = true;
                } catch (IOException | RuntimeException e) {
                    log.warn("Cannot offload a tool result: {}", e.getMessage()); // the original stays in place
                }
            }
        }
        return changed;
    }

    String pointer(String path, String text) {
        return pointer(path, text, options.previewChars());
    }

    /** @param preview characters kept at each end */
    String pointer(String path, String text, int preview) {
        int p = Math.min(preview, text.length() / 2);
        return OFFLOADED + path + " (" + text.length() + " chars); read it with read_file if you need more.]\n"
                + text.substring(0, p) + "\n[...]\n" + text.substring(text.length() - p);
    }

    /** Blanks the bodies of tool results older than the last {@code keepTurns} turns; an offload pointer stays. */
    int clearOld(ArrayNode msgs, int keepTurns) {
        int cut = cutIndex(msgs, keepTurns);
        int cleared = 0;
        for (int i = 0; i < cut; i++) {
            JsonNode content = msgs.get(i).path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                if (!"tool_result".equals(block.path("type").asText())) continue;
                String text = block.path("content").isTextual() ? block.path("content").asText()
                        : block.path("content").toString();
                if (text.startsWith(OFFLOADED)) {
                    int nl = text.indexOf('\n');
                    if (nl < 0) continue;
                    ((ObjectNode) block).put("content", text.substring(0, nl));
                    cleared++;
                } else if (text.length() > CLEARED.length() + 50) {
                    ((ObjectNode) block).put("content", CLEARED);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /**
     * Summarizes everything but the pinned first message and the latest turn.
     *
     * @param beforeClear the log before old tool results were cleared (same indexes): what the summary is made from
     */
    private boolean summarize(ContextTurn t, String focus, ArrayNode beforeClear) {
        ArrayNode msgs = t.log();
        int head = headSize(msgs);
        ArrayNode rest = mapper.createArrayNode();
        for (int i = head; i < msgs.size(); i++) rest.add(msgs.get(i));
        ArrayNode original = null;
        if (beforeClear != null && beforeClear.size() == msgs.size()) {
            original = mapper.createArrayNode();
            for (int i = head; i < beforeClear.size(); i++) original.add(beforeClear.get(i));
        }
        CompactResult r = manager.compactNow(rest, focus, t.usageSink(), null, original);
        if (!r.changed()) return false;
        ArrayNode out = mapper.createArrayNode();
        if (head > 0) {
            out.add(msgs.get(0));
            if (head == 2) out.add(msgs.get(1));
            else {
                ObjectNode ack = out.addObject().put("role", "assistant");
                ack.putArray("content").addObject().put("type", "text").put("text", ACK);
            }
        }
        out.addAll(rest);
        msgs.removeAll();
        msgs.addAll(out);
        return true;
    }

    /**
     * Messages that are never compacted: the first user message (0 when the log does not start with one, e.g. after a
     * summary), plus the acknowledgement that separates it from a summary.
     */
    private static int headSize(ArrayNode msgs) {
        if (msgs.isEmpty() || !isPlainUserText(msgs.get(0)) || text(msgs.get(0)).startsWith(SUMMARY_PREFIX)) return 0;
        if (msgs.size() > 2 && "assistant".equals(msgs.get(1).path("role").asText())
                && ACK.equals(text(msgs.get(1))) && text(msgs.get(2)).startsWith(SUMMARY_PREFIX)) {
            return 2;
        }
        return 1;
    }

    private static String text(JsonNode msg) {
        JsonNode c = msg.path("content");
        return c.isTextual() ? c.asText() : c.path(0).path("text").asText("");
    }

    // ---- todos --------------------------------------------------------------------------------------------------

    /** The messages of the request: the log with the todo list appended to the last user message; the log is not changed. */
    private ArrayNode withTodos(ContextTurn t) {
        String list = TodoStore.render(todos.read(t.chatId(), t.agentKey()));
        ArrayNode msgs = t.log();
        if (list == null || msgs.isEmpty()) return msgs;
        JsonNode last = msgs.get(msgs.size() - 1);
        if (!"user".equals(last.path("role").asText())) return msgs;
        ObjectNode copy = last.deepCopy();
        ArrayNode blocks = mapper.createArrayNode();
        JsonNode c = copy.path("content");
        if (c.isTextual()) blocks.addObject().put("type", "text").put("text", c.asText());
        else if (c.isArray()) blocks.addAll((ArrayNode) c);
        blocks.addObject().put("type", "text").put("text", "<todo_list>\n" + list + "</todo_list>");
        copy.set("content", blocks);
        ArrayNode out = mapper.createArrayNode();
        for (int i = 0; i < msgs.size() - 1; i++) out.add(msgs.get(i));
        out.add(copy);
        return out;
    }

    // ---- turns --------------------------------------------------------------------------------------------------

    private static int cutIndex(ArrayNode msgs, int keepTurns) {
        int turns = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (isPlainUserText(msgs.get(i)) && ++turns == Math.max(1, keepTurns)) return i;
        }
        return 0;
    }

    private static boolean isPlainUserText(JsonNode msg) {
        if (!"user".equals(msg.path("role").asText())) return false;
        JsonNode c = msg.path("content");
        if (c.isTextual()) return true;
        if (!c.isArray() || c.isEmpty()) return false;
        for (JsonNode b : c) if (!"text".equals(b.path("type").asText())) return false;
        return true;
    }
}

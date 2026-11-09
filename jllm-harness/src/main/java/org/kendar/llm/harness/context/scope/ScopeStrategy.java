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

    /**
     * @param offloadChars         tool results longer than this are offloaded when the request is over budget
     * @param overflowOffloadChars the same threshold on the forced pass after a provider overflow
     * @param previewChars         head and tail kept in the context for an offloaded result
     * @param safetyPercent        share of the window kept free as a safety margin
     * @param reserveCap           upper bound (tokens) of the room reserved for the answer
     */
    public record Options(int offloadChars, int overflowOffloadChars, int previewChars, int safetyPercent,
                          int reserveCap) {

        public static Options defaults() {
            return new Options(20_000, 2_000, 1_500, 2, 4_096);
        }
    }

    private final HarnessConfig config;
    private final ContextManager manager;
    private final ObjectMapper mapper;
    private final TodoStore todos;
    private final Options options;
    private final BiFunction<String, String, Path> offloadDir;
    private final Function<Path, String> display;
    private final List<HarnessTool> tools;

    /**
     * @param offloadDir {@code (chatId, agentKey)} to a directory the agent's file tools can read
     * @param display    how a path is shown to the model (container paths differ from host paths)
     */
    public ScopeStrategy(HarnessConfig config, ContextManager manager, ObjectMapper mapper, TodoStore todos,
                         Options options, BiFunction<String, String, Path> offloadDir, Function<Path, String> display) {
        this.config = config;
        this.manager = manager;
        this.mapper = mapper;
        this.todos = todos;
        this.options = options;
        this.offloadDir = offloadDir;
        this.display = display;
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
        if (config.compaction().auto()) {
            long budget = budget(t.prefix());
            if (estimate(t.log(), t.prefix(), t.lastUsage()) > budget) {
                changed = shrink(t, budget, options.offloadChars(), config.keepRecentTurns(), false);
            }
        }
        return new ContextView(withTodos(t), changed, changed);
    }

    @Override
    public ContextView onOverflow(ContextTurn t) {
        boolean changed = shrink(t, 0, options.overflowOffloadChars(), 1, true);
        return changed ? new ContextView(withTodos(t), true, true) : null;
    }

    @Override
    public CompactResult compactNow(ContextTurn t, String focus) {
        long before = estimate(t.log(), null, null);
        int cleared = clearOld(t.log(), 1);
        boolean summarized = summarize(t, focus);
        return new CompactResult(cleared > 0 || summarized, before, estimate(t.log(), null, null), cleared);
    }

    @Override
    public long contextTokens(String chatId, String agentKey, ArrayNode log) {
        return estimate(log, null, null);
    }

    // ---- budget -------------------------------------------------------------------------------------------------

    /** Window minus the answer reserve and the safety margin minus the fixed part of the request. */
    long budget(RequestPrefix prefix) {
        long window = config.contextBudgetTokens();
        long reserve = Math.min(window / 8, Math.min(options.reserveCap(), config.maxTokens()));
        long safety = window * options.safetyPercent() / 100;
        return window - reserve - safety - fixedTokens(prefix);
    }

    private static long fixedTokens(RequestPrefix prefix) {
        if (prefix == null) return 0;
        long chars = (prefix.pinned() == null ? 0 : prefix.pinned().length())
                + (prefix.extra() == null ? 0 : prefix.extra().length());
        for (HarnessTool tool : prefix.tools()) {
            chars += tool.name().length() + tool.description().length() + tool.inputSchema().toString().length();
        }
        return chars / 4;
    }

    private static long estimate(ArrayNode messages, RequestPrefix prefix, org.kendar.llm.harness.llm.Usage last) {
        long chars = messages.toString().length() / 4 + fixedTokens(prefix);
        return last == null ? chars : Math.max(chars, last.contextTokens());
    }

    // ---- the trim order -----------------------------------------------------------------------------------------

    private boolean shrink(ContextTurn t, long budget, int offloadChars, int keepTurns, boolean force) {
        ArrayNode msgs = t.log();
        boolean changed = false;
        // 1. offload: first outside the latest turn, then everywhere
        changed |= offload(t, offloadChars, latestTurn(msgs));
        if (!force && estimate(msgs, t.prefix(), null) <= budget) return changed;
        changed |= offload(t, offloadChars, msgs.size());
        if (!force && estimate(msgs, t.prefix(), null) <= budget) return changed;
        // 2. clear old tool results
        changed |= clearOld(msgs, keepTurns) > 0;
        if (!force && estimate(msgs, t.prefix(), null) <= budget) return changed;
        // 3. summarize
        try {
            changed |= summarize(t, null);
        } catch (RuntimeException e) {
            log.warn("Summarization failed: {}", e.getMessage());
            t.sink().emit("warning", mapper.createObjectNode().put("message",
                    "Context summarization failed: " + e.getMessage()));
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
        Path dir = null;
        for (int i = 0; i < upTo && i < t.log().size(); i++) {
            JsonNode content = t.log().get(i).path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                if (!"tool_result".equals(block.path("type").asText()) || !block.path("content").isTextual()) continue;
                String text = block.path("content").asText();
                if (text.length() <= threshold || text.startsWith(OFFLOADED)) continue;
                try {
                    if (dir == null) dir = offloadDir.apply(t.chatId(), t.agentKey()).resolve("offload");
                    Files.createDirectories(dir);
                    String id = block.path("tool_use_id").asText("result").replaceAll("[^A-Za-z0-9._-]", "_");
                    Path file = dir.resolve(id + ".txt");
                    Files.writeString(file, text, StandardCharsets.UTF_8);
                    ((ObjectNode) block).put("content", pointer(display.apply(file), text));
                    changed = true;
                } catch (IOException | RuntimeException e) {
                    log.warn("Cannot offload a tool result: {}", e.getMessage()); // the original stays in place
                }
            }
        }
        return changed;
    }

    String pointer(String path, String text) {
        int p = Math.min(options.previewChars(), text.length() / 2);
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

    /** Summarizes everything but the pinned first message and the latest turn. */
    private boolean summarize(ContextTurn t, String focus) {
        ArrayNode msgs = t.log();
        int head = headSize(msgs);
        ArrayNode rest = mapper.createArrayNode();
        for (int i = head; i < msgs.size(); i++) rest.add(msgs.get(i));
        CompactResult r = manager.compactNow(rest, focus, t.usageSink());
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

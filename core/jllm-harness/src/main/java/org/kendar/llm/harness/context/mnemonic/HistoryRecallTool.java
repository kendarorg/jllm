package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Mask;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * {@code history_recall}: the way back to everything the mnemonic strategy masked or moved out of the prompt, and
 * into the transcripts of sub-agent runs.
 */
final class HistoryRecallTool implements HarnessTool {

    static final String NAME = "history_recall";
    private static final int RESULTS = 8;
    private static final int FILE_ENTRIES = 30;

    private final MnemonicStrategy strategy;
    private final SubAgentRunLog runs;
    private final ObjectMapper mapper;
    private final JsonNode schema = SimpleTool.schema(
            "query?", "Words to search for in what was moved out of the prompt; several variants separated by ' | '. "
                    + "With id: search inside that text only (matching lines and their offsets)",
            "id?", "A recall id from a receipt or a search result (r…, a…, t…, m…) or a sub-agent runId: returns the "
                    + "original text",
            "file?", "A file path: the reads, writes and edits of that file, oldest first",
            "scope?", "A sub-agent runId: search inside that run's transcript instead",
            "offset:integer?", "With id: character offset to continue a long text from");
    /** Matching lines shown by a search inside one recalled text. */
    private static final int LINE_HITS = 20;
    /** Lines shown before and after each matching line. */
    private static final int CONTEXT_LINES = 1;
    /** Longer lines are cut to this many chars around the match. */
    private static final int LINE_CHARS = 300;
    /** Indexes of sub-agent run transcripts, by run id. */
    private final Map<String, RecallIndex> runIndexes = new ConcurrentHashMap<>();

    HistoryRecallTool(MnemonicStrategy strategy, SubAgentRunLog runs, ObjectMapper mapper) {
        this.strategy = strategy;
        this.runs = runs;
        this.mapper = mapper;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "Recall content that was masked or moved out of the prompt to save context: by recall id (word for "
                + "word, paged), by search query, by id and query (search inside that text), or by file path. "
                + "Receipts like '[masked: … recall id=r…]' point here.";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public String group() { return "Memory"; }
    @Override public ToolKind kind() { return ToolKind.READ_ONLY; }

    @Override
    public String execute(ToolContext ctx, JsonNode input) {
        String id = input.path("id").asText("").strip();
        String query = input.path("query").asText("").strip();
        String file = input.path("file").asText("").strip();
        String scope = input.path("scope").asText("").strip();
        int offset = Math.max(0, input.path("offset").asInt(0));
        if (!scope.isEmpty() && !query.isEmpty()) return searchRun(ctx.chatId(), scope, query);
        MnemonicStrategy.Agent a = strategy.agentFor(ctx.chatId(), ctx.run() == null ? null : ctx.run().contextKey());
        if (!id.isEmpty()) {
            String text = textOf(ctx.chatId(), a, id);
            if (text == null) return "Unknown recall id " + id + ".";
            int limit = strategy.recallChars(a);
            return query.isEmpty() ? page(text, offset, id, limit) : searchIn(text, id, query, limit);
        }
        if (a == null || a.log == null) return "Nothing has been moved out of the prompt yet.";
        if (!file.isEmpty()) return file(a, file);
        if (!query.isEmpty()) return search(a, query);
        throw new IllegalArgumentException("Give one of 'id', 'query' or 'file'");
    }

    private String search(MnemonicStrategy.Agent a, String query) {
        ArrayNode log;
        IntPredicate allowed;
        synchronized (a) {
            log = a.log;
            allowed = MnemonicStrategy.searchable(a.state);
        }
        a.index.update(list(log));
        List<RecallIndex.Hit> hits = a.index.search(variants(query), allowed, RESULTS);
        if (hits.isEmpty()) return "No match in what was moved out of the prompt (visible messages are not searched).";
        StringBuilder sb = new StringBuilder(hits.size() + " result(s); use {\"id\": …} for the full text:\n");
        int n = 1;
        for (RecallIndex.Hit h : hits) {
            sb.append(n++).append(". id=").append(idOf(a.state, log, h.message())).append(" (message ")
                    .append(h.message()).append(", ").append(Messages.role(log.get(h.message())))
                    .append(h.strong() ? ", all words" : "").append(")\n   ").append(h.snippet()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String searchRun(String chatId, String runId, String query) {
        SubAgentRunData run = run(chatId, runId);
        if (run == null) return "No sub-agent run " + runId + " in this chat.";
        List<JsonNode> messages = transcript(run);
        RecallIndex index = runIndexes.computeIfAbsent(runId, k -> new RecallIndex());
        index.update(messages);
        List<RecallIndex.Hit> hits = index.search(variants(query), i -> true, RESULTS);
        if (hits.isEmpty()) return "No match in run " + runId + ".";
        StringBuilder sb = new StringBuilder(hits.size() + " result(s) in run " + runId + "; use {\"id\": \"" + runId
                + "\"} for the whole transcript:\n");
        int n = 1;
        for (RecallIndex.Hit h : hits) {
            sb.append(n++).append(". message ").append(h.message()).append(" (")
                    .append(Messages.role(messages.get(h.message()))).append(")\n   ").append(h.snippet()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Word-for-word content of a recall id: the agent's own, else (a sub-agent) its parent's, whose receipts and
     * recall index came with the copied context, else a sub-agent run of the chat. @return null for an unknown id
     */
    private String textOf(String chatId, MnemonicStrategy.Agent a, String id) {
        String own = fromAgent(a, id);
        if (own != null) return own;
        String inherited = fromAgent(strategy.parentOf(chatId, a), id);
        if (inherited != null) return inherited;
        SubAgentRunData run = run(chatId, id);
        if (run == null) return null;
        StringBuilder sb = new StringBuilder("Sub-agent run ").append(id).append(" (").append(run.status())
                .append(")\nPrompt: ").append(run.prompt()).append("\nAnswer: ").append(run.answer())
                .append("\n\nTranscript:\n");
        for (JsonNode m : transcript(run)) sb.append(Messages.role(m)).append(": ").append(Messages.text(m)).append("\n\n");
        return sb.toString();
    }

    /** The content of {@code id} among the masks and messages of agent {@code a}; null when it is not there. */
    private String fromAgent(MnemonicStrategy.Agent a, String id) {
        if (a == null || a.log == null) return null;
        synchronized (a) {
            ArrayNode log = a.log;
            Mask m = a.state.byId(id);
            if (m != null && m.index() < log.size()) {
                JsonNode block = log.get(m.index()).path("content").path(m.block());
                return switch (m.kind()) {
                    case MnemonicState.TOOL_RESULT -> Messages.resultText(block);
                    case MnemonicState.TOOL_INPUT -> Messages.pretty(block.path("input"));
                    default -> block.path("thinking").asText("");
                };
            }
            if (id.startsWith("m")) {
                for (int i = 0; i < log.size(); i++) {
                    if (SegmentSwitcher.messageId(i, log.get(i)).equals(id)) {
                        return Messages.role(log.get(i)) + ":\n" + Messages.text(log.get(i));
                    }
                }
            }
        }
        return null;
    }

    /** Timeline of a file: every tool call whose input names it. */
    private String file(MnemonicStrategy.Agent a, String path) {
        String wanted = path.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        int n = 0;
        synchronized (a) {
            ArrayNode log = a.log;
            for (int i = 0; i < log.size() && n < FILE_ENTRIES; i++) {
                JsonNode c = log.get(i).path("content");
                if (!c.isArray()) continue;
                for (JsonNode b : c) {
                    if (!"tool_use".equals(b.path("type").asText()) || !names(b.path("input"), wanted)) continue;
                    sb.append("- message ").append(i).append(": ").append(Messages.callSummary(b));
                    String result = resultId(a.state, log, i, b.path("id").asText());
                    if (result != null) sb.append(" -> id=").append(result);
                    sb.append('\n');
                    n++;
                }
            }
        }
        return n == 0 ? "No tool call names " + path + "." : sb.toString().stripTrailing();
    }

    private static boolean names(JsonNode input, String path) {
        for (JsonNode v : input) {
            if (!v.isTextual()) continue;
            String s = v.asText().replace('\\', '/');
            if (s.equals(path) || s.endsWith("/" + path) || path.endsWith("/" + s)) return true;
        }
        return false;
    }

    /** Recall id of the result of tool call {@code toolUseId} made in message {@code i}. */
    private static String resultId(MnemonicState st, ArrayNode log, int i, String toolUseId) {
        if (i + 1 >= log.size()) return null;
        JsonNode c = log.get(i + 1).path("content");
        if (!c.isArray()) return null;
        for (int k = 0; k < c.size(); k++) {
            if (!toolUseId.equals(c.get(k).path("tool_use_id").asText())) continue;
            for (Mask m : st.masks) if (m.index() == i + 1 && m.block() == k) return m.id();
            return SegmentSwitcher.messageId(i + 1, log.get(i + 1));
        }
        return null;
    }

    /** The id a search hit is reported under: the single mask of the message, else the whole message. */
    private static String idOf(MnemonicState st, ArrayNode log, int i) {
        Mask only = null;
        int count = 0;
        for (Mask m : st.masks) {
            if (m.index() == i && !MnemonicState.THINKING.equals(m.kind())) {
                only = m;
                count++;
            }
        }
        return count == 1 ? only.id() : SegmentSwitcher.messageId(i, log.get(i));
    }

    /**
     * One page of {@code text}. A page that does not reach the end says how much is left; the first one also asks
     * for a search before concluding that something is absent (models read page 1 and stop).
     *
     * @param limit characters of this page: what fits the agent's window now, at most the configured page
     */
    static String page(String text, int offset, String id, int limit) {
        if (offset >= text.length()) return "(end of " + id + ")";
        int end = Math.min(text.length(), offset + limit);
        String out = text.substring(offset, end);
        if (end == text.length()) return out;
        String next = "{\"id\": \"" + id + "\", \"offset\": " + end + "}";
        int rest = text.length() - end;
        if (offset > 0) return out + "\n…[" + rest + " more chars: " + next + "]";
        int pages = (text.length() + limit - 1) / limit;
        return out + "\n…[page 1 of " + pages + ": " + rest + " more chars, ~" + lines(text.substring(end))
                + " lines not shown. Search it with {\"id\": \"" + id + "\", \"query\": \"…\"} before concluding "
                + "something is absent; next page: " + next + "]";
    }

    /**
     * The lines of {@code text} (recall id {@code id}) that match one of the ' | ' variants of {@code query}: a line
     * matches a variant when it contains each of its words, case-insensitive (also inside identifiers:
     * "retention days" matches {@code retention_days}). Each hit shows {@value #CONTEXT_LINES} line of context and the
     * char offset to continue from with {@code {"id", "offset"}}.
     */
    static String searchIn(String text, String id, String query, int limit) {
        List<String[]> words = new ArrayList<>();
        for (String v : variants(query)) words.add(v.toLowerCase(Locale.ROOT).split("\\s+"));
        String[] lines = text.split("\n", -1);
        int[] starts = new int[lines.length];
        for (int i = 1; i < lines.length; i++) starts[i] = starts[i - 1] + lines[i - 1].length() + 1;
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (matches(lines[i].toLowerCase(Locale.ROOT), words)) hits.add(i);
        String size = text.length() + " chars, " + lines.length + " lines";
        if (hits.isEmpty()) {
            return "No match for '" + query + "' in " + id + " (" + size + "); try other words, or page it with "
                    + "{\"id\": \"" + id + "\", \"offset\": 0}.";
        }
        StringBuilder sb = new StringBuilder().append(hits.size()).append(" matching line(s) for '").append(query)
                .append("' in ").append(id).append(" (").append(size).append("); continue from a hit with {\"id\": \"")
                .append(id).append("\", \"offset\": N}:\n");
        int shown = 0;
        int printed = -1; // last line already printed
        for (int h : hits) {
            if (shown == LINE_HITS || sb.length() > limit) break;
            int from = Math.max(printed + 1, h - CONTEXT_LINES);
            int to = Math.min(lines.length - 1, h + CONTEXT_LINES);
            if (from > printed + 1 && printed >= 0) sb.append("  …\n");
            for (int i = from; i <= to; i++) {
                boolean hit = hits.contains(i);
                sb.append(hit ? "> " : "  ").append("line ").append(i + 1).append(", offset ").append(starts[i])
                        .append(": ").append(clip(lines[i], words)).append('\n');
            }
            printed = Math.max(printed, to);
            shown++;
        }
        int left = 0;
        for (int h : hits) if (h > printed) left++;
        if (left > 0) sb.append("…[").append(left).append(" more matching line(s) after line ").append(printed + 1)
                .append(": narrow the query or continue with an offset]");
        return sb.toString().stripTrailing();
    }

    private static boolean matches(String lowerLine, List<String[]> variants) {
        for (String[] words : variants) {
            boolean all = true;
            for (String w : words) {
                if (!lowerLine.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    /** A line cut to {@value #LINE_CHARS} chars around its first match. */
    private static String clip(String line, List<String[]> variants) {
        if (line.length() <= LINE_CHARS) return line;
        String lower = line.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String[] words : variants) {
            int i = lower.indexOf(words[0]);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        int from = Math.max(0, Math.min(Math.max(0, at) - LINE_CHARS / 3, line.length() - LINE_CHARS));
        int to = from + LINE_CHARS;
        return (from > 0 ? "…" : "") + line.substring(from, to) + (to < line.length() ? "…" : "");
    }

    private static int lines(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    private SubAgentRunData run(String chatId, String runId) {
        for (SubAgentRunData r : runs.list(chatId)) if (r.id().equals(runId)) return r;
        return null;
    }

    private List<JsonNode> transcript(SubAgentRunData run) {
        List<JsonNode> out = new ArrayList<>();
        if (run.messagesJson() == null) return out;
        for (String line : run.messagesJson().split("\n")) {
            if (line.isBlank()) continue;
            try {
                out.add(mapper.readTree(line));
            } catch (Exception e) {
                // a corrupt line is skipped, the rest of the transcript is still searchable
            }
        }
        return out;
    }

    private static List<String> variants(String query) {
        return Arrays.stream(query.split("\\s\\|\\s")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static List<JsonNode> list(ArrayNode log) {
        List<JsonNode> out = new ArrayList<>(log.size());
        log.forEach(out::add);
        return out;
    }
}

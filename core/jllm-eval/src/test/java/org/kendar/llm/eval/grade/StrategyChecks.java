package org.kendar.llm.eval.grade;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.eval.run.RecordedRequest;
import org.kendar.llm.eval.run.TrialTrace;
import org.kendar.llm.harness.agent.AgentEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Invariants of the context strategies. The strings are the ones the harness emits (context packages of
 * jllm-harness: mnemonic, scope, compaction) and must be kept in step with them.
 */
public final class StrategyChecks {

    /** Start of a mnemonic mask receipt (tool results and tool inputs). */
    public static final String MASKED = "[masked";
    /** Marker inside a receipt, followed by the id to recall. */
    public static final String RECALL_MARKER = "recall id=";
    public static final String RECALL_TOOL = "history_recall";
    /** Tools whose input the mnemonic receipt guard inspects. */
    public static final Set<String> GUARDED_TOOLS = Set.of("write_file", "edit_file", "bash");
    /** Start of an offload pointer left by the scope strategy. */
    public static final String OFFLOADED = "[Output offloaded to ";
    public static final String TODO_TOOL = "todo_write";
    /** Tag wrapping the task list appended to the last user message by the scope strategy. */
    public static final String TODO_TAG = "<todo_list>";
    public static final String SUMMARY_PREFIX = "[Summary of the earlier conversation]";
    /** Start of the summary of the older steps of a request in progress (an in-turn compaction). */
    public static final String TURN_SUMMARY_PREFIX = "[Summary of the earlier steps of this request]";
    /** Headings of the whole summary template (compaction-template.md). */
    public static final List<String> SUMMARY_SECTIONS = List.of("## Objective", "## Requirements", "## Decisions",
            "## Work State", "### Completed", "### Active", "### Blocked", "## Next Move", "## Relevant Files",
            "## Important Context");
    /**
     * Headings every summary has: compaction-rules.md lets the model omit a section with nothing to say, so the
     * others (e.g. Decisions on a session that decided nothing, Next Move once the request is answered) are not
     * required.
     */
    public static final List<String> REQUIRED_SUMMARY_SECTIONS = List.of("## Objective", "## Work State");

    private StrategyChecks() {
    }

    private static Check check(String name, Function<TrialContext, CheckResult> body) {
        return Check.of(name, Check.Tier.STATE, body);
    }

    // ---- mnemonic -------------------------------------------------------------------------------------------

    /**
     * Earlier messages stay byte-identical from one request to the next, except messages that became a mask receipt
     * and boundaries excused by a segment, emergency or manual compaction event (one boundary per event).
     */
    public static Check mnemonicLogNeverRewritten() {
        String n = "mnemonicLogNeverRewritten";
        return check(n, ctx -> {
            List<RecordedRequest> reqs = ctx.trace().mainRequests();
            long excuses = ctx.trace().events("compaction").stream().filter(e -> {
                JsonNode d = e.data();
                String kind = d == null ? "" : d.path("kind").asText("");
                // a user /compact may rewrite the log (it carries no kind or strategy)
                return kind.equals("segment") || kind.equals("emergency")
                        || (d != null && d.path("manual").asBoolean(false))
                        || (d != null && !"mnemonic".equals(d.path("strategy").asText("mnemonic")));
            }).count();
            long used = 0;
            for (int i = 1; i < reqs.size(); i++) {
                var a = reqs.get(i - 1).messages();
                var b = reqs.get(i).messages();
                boolean rewritten = b.size() < a.size();
                for (int k = 0; k < Math.min(a.size(), b.size()) && !rewritten; k++) {
                    if (a.get(k).equals(b.get(k))) continue;
                    if (!(Msgs.masked(b.get(k)) && !Msgs.masked(a.get(k)))) rewritten = true;
                }
                if (rewritten && ++used > excuses) {
                    return CheckResult.fail(n, "request " + reqs.get(i).index() + " rewrote earlier messages without a compaction");
                }
            }
            return CheckResult.pass(n, reqs.size() < 2 ? "fewer than two requests" : "log append-only apart from masking");
        });
    }

    /**
     * Masked receipts were sent and a {@code history_recall} call returned content (containing
     * {@code expectedContent} when not null).
     */
    public static Check mnemonicRecallFindsMasked(String expectedContent) {
        String n = "mnemonicRecallFindsMasked";
        return check(n, ctx -> {
            boolean anyMasked = ctx.trace().mainRequests().stream()
                    .anyMatch(r -> stream(r).anyMatch(Msgs::masked));
            if (!anyMasked) return CheckResult.fail(n, "no content was masked");
            List<String> outputs = results(ctx.trace(), RECALL_TOOL, false);
            if (outputs.isEmpty()) return CheckResult.fail(n, "no successful " + RECALL_TOOL + " result");
            if (expectedContent != null && outputs.stream().noneMatch(o -> o.contains(expectedContent))) {
                return CheckResult.fail(n, RECALL_TOOL + " did not return '" + expectedContent + "'");
            }
            return CheckResult.pass(n, outputs.size() + " recall(s) returned content");
        });
    }

    /**
     * Guarded write calls whose input holds a masked receipt were blocked (error result) and no workspace file
     * contains a receipt.
     *
     * @param requireAttempt fail when no such write was attempted
     */
    public static Check mnemonicGuardBlocksMaskedWrites(boolean requireAttempt) {
        String n = "mnemonicGuardBlocksMaskedWrites";
        return check(n, ctx -> {
            int attempts = 0;
            for (AgentEvent call : ctx.trace().toolCalls()) {
                String tool = TrialTrace.toolName(call);
                String in = call.data().path("input").toString();
                if (!GUARDED_TOOLS.contains(tool) || !in.contains(MASKED) || !in.contains(RECALL_MARKER)) continue;
                attempts++;
                AgentEvent res = ctx.trace().events("tool_result").stream()
                        .filter(e -> e.data() != null && e.data().path("id").asText("").equals(call.data().path("id").asText("")))
                        .findFirst().orElse(null);
                if (res == null || !res.data().path("isError").asBoolean(false)) {
                    return CheckResult.fail(n, tool + " with a masked receipt was not blocked");
                }
            }
            String leaked = receiptInWorkspace(ctx.workspace());
            if (leaked != null) return CheckResult.fail(n, "workspace file " + leaked + " contains a masked receipt");
            if (attempts == 0 && requireAttempt) return CheckResult.fail(n, "no write with a masked receipt was attempted");
            return CheckResult.pass(n, attempts + " attempt(s), all blocked");
        });
    }

    // ---- scope ----------------------------------------------------------------------------------------------

    /**
     * Large outputs were offloaded: an offload pointer reached the model, and no tool result older than the last
     * message holds more than {@code maxChars} characters in the last request.
     */
    public static Check scopeOffloadsLargeOutputs(int maxChars) {
        String n = "scopeOffloadsLargeOutputs(" + maxChars + ")";
        return check(n, ctx -> {
            List<RecordedRequest> reqs = ctx.trace().mainRequests();
            if (reqs.isEmpty()) return CheckResult.notMeasurable(n, "no requests");
            if (reqs.stream().noneMatch(r -> stream(r).anyMatch(m -> Msgs.text(m).contains(OFFLOADED)))) {
                return CheckResult.fail(n, "no offload pointer was ever sent");
            }
            var last = reqs.get(reqs.size() - 1).messages();
            for (int i = 0; i < last.size() - 1; i++) {
                JsonNode c = last.get(i).path("content");
                if (!c.isArray()) continue;
                for (JsonNode b : c) {
                    if (!"tool_result".equals(b.path("type").asText())) continue;
                    String t = Msgs.blockText(b.path("content"));
                    if (t.length() > maxChars && !t.startsWith(OFFLOADED)) {
                        return CheckResult.fail(n, "message " + i + " of the last request holds a " + t.length()
                                + "-char tool result");
                    }
                }
            }
            return CheckResult.pass(n, "large outputs offloaded");
        });
    }

    /** A user message made of text only: the start of a turn (a tool_result carrier is not). */
    private static boolean plainUser(JsonNode m) {
        if (!"user".equals(Msgs.role(m))) return false;
        JsonNode c = m.path("content");
        if (c.isTextual()) return true;
        if (!c.isArray() || c.isEmpty()) return false;
        for (JsonNode b : c) if (!"text".equals(b.path("type").asText())) return false;
        return true;
    }

    /** The first message is identical in every main request and the task list is in the last one. */
    public static Check scopePinned() {
        String n = "scopePinned";
        return check(n, ctx -> {
            List<RecordedRequest> reqs = ctx.trace().mainRequests();
            if (reqs.isEmpty() || reqs.get(0).messages().isEmpty()) return CheckResult.notMeasurable(n, "no requests");
            JsonNode first = reqs.get(0).messages().get(0);
            for (RecordedRequest r : reqs) {
                if (r.messages().isEmpty() || !r.messages().get(0).equals(first)) {
                    return CheckResult.fail(n, "the first message changed in request " + r.index());
                }
            }
            if (ctx.trace().toolCalls().stream().noneMatch(e -> TODO_TOOL.equals(TrialTrace.toolName(e)))) {
                return CheckResult.fail(n, "the task list was never written (" + TODO_TOOL + ")");
            }
            // the list is appended to the log at the start of each turn and when it changes: the turn in progress
            // of the last request must hold it
            var msgs = reqs.get(reqs.size() - 1).messages();
            boolean found = false;
            for (int i = msgs.size() - 1; i >= 0 && !found; i--) {
                JsonNode m = msgs.get(i);
                if ("user".equals(Msgs.role(m)) && Msgs.text(m).contains(TODO_TAG)) found = true;
                if (plainUser(m)) break;
            }
            if (!found) return CheckResult.fail(n, "the task list is missing from the turn in progress");
            return CheckResult.pass(n, "first message and task list pinned");
        });
    }

    // ---- compaction -----------------------------------------------------------------------------------------

    /** Every conversation summary sent to the model contains all {@link #SUMMARY_SECTIONS}; at least one exists. */
    public static Check compactionSummaryHasSections() {
        String n = "compactionSummaryHasSections";
        return check(n, ctx -> {
            Set<String> summaries = new LinkedHashSet<>();
            for (RecordedRequest r : ctx.trace().mainRequests()) {
                stream(r).filter(m -> "user".equals(Msgs.role(m))).map(Msgs::text)
                        .filter(StrategyChecks::isSummary).forEach(summaries::add);
            }
            if (summaries.isEmpty()) return CheckResult.fail(n, "no summary was produced");
            for (String s : summaries) {
                for (String section : REQUIRED_SUMMARY_SECTIONS) {
                    if (!s.contains(section)) return CheckResult.fail(n, "summary lacks '" + section + "'");
                }
            }
            return CheckResult.pass(n, summaries.size() + " summary(ies) with the required sections");
        });
    }

    /** The final answer is a reply, not a copy of the checkpoint the model was given. */
    public static Check answerIsNotASummary() {
        String n = "answerIsNotASummary";
        return check(n, ctx -> {
            String a = ctx.trace().finalAnswer();
            return a != null && isSummary(a.stripLeading())
                    ? CheckResult.fail(n, "the answer starts with a summary checkpoint")
                    : CheckResult.pass(n, "the answer is not a summary");
        });
    }

    private static boolean isSummary(String text) {
        return text.startsWith(SUMMARY_PREFIX) || text.startsWith(TURN_SUMMARY_PREFIX);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static Stream<JsonNode> stream(RecordedRequest r) {
        List<JsonNode> l = new ArrayList<>();
        r.messages().forEach(l::add);
        return l.stream();
    }

    private static List<String> results(TrialTrace t, String tool, boolean errors) {
        List<String> out = new ArrayList<>();
        for (AgentEvent e : t.events("tool_result")) {
            JsonNode d = e.data();
            if (d == null || !tool.equals(d.path("name").asText())) continue;
            if (d.path("isError").asBoolean(false) != errors) continue;
            String o = d.path("output").asText("");
            if (!o.isBlank()) out.add(o);
        }
        return out;
    }

    private static String receiptInWorkspace(Path ws) {
        if (ws == null || !Files.isDirectory(ws)) return null;
        try (Stream<Path> s = Files.walk(ws)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                if (Files.size(p) > 5_000_000) continue;
                String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (t.contains(MASKED) && t.contains(RECALL_MARKER)) return ws.relativize(p).toString().replace('\\', '/');
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan the workspace: " + e.getMessage(), e);
        }
        return null;
    }
}

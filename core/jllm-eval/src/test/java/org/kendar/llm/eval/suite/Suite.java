package org.kendar.llm.eval.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.eval.EvalModels;
import org.kendar.llm.eval.EvalRun;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.FakeServer;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.fixture.SampleTools;
import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.eval.grade.CheckResult;
import org.kendar.llm.eval.grade.Checks;
import org.kendar.llm.eval.grade.Judge;
import org.kendar.llm.eval.grade.RetentionChecks;
import org.kendar.llm.eval.grade.TrialContext;
import org.kendar.llm.eval.grade.judge.LlmJudge;
import org.kendar.llm.eval.grade.judge.Rubric;
import org.kendar.llm.eval.run.RecordedRequest;
import org.kendar.llm.eval.run.TrialTrace;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Shared helpers of the generated cases: workspace setup, settings files and checks the core facade lacks. */
public final class Suite {

    private static final ObjectMapper M = new ObjectMapper();
    private static LlmJudge judge;

    private Suite() {
    }

    // ---- features (the number is the heading number in FEATURES.md) -------------------------------------------
    public static final String AGENT_LOOP = "1.1-agent-loop";
    public static final String CUTOFF = "1.2-cut-off-responses";
    public static final String CUSTOM_TOOLS = "1.5-custom-tools";
    public static final String FILE_TOOLS = "1.6-file-tools";
    public static final String SHELL = "1.7-shell";
    public static final String MODES = "1.9-permission-modes";
    public static final String RULES = "1.11-permission-rules";
    public static final String HOOKS = "1.12-hooks";
    public static final String STUCK = "1.13-stuck-detection";
    public static final String MCP = "1.14-mcp";
    public static final String REST = "1.15-rest";
    public static final String TOOL_GROUPS = "1.17-tool-groups";
    public static final String SAFETY = "1.18-output-safety";
    public static final String SUBAGENTS = "1.19-sub-agents";
    public static final String SKILLS = "1.20-skills";
    public static final String LIMITS = "1.21-output-limits";
    public static final String SESSIONS = "1.22-sessions";
    public static final String COMMANDS = "1.23-commands-plugins";
    public static final String CACHE = "3-prompt-cache";
    public static final String PDF = "2.1-pdf";
    public static final String DOCS = "2.2-docs";
    public static final String JS = "2.3-js";
    public static final String WORKFLOWS = "2.4-workflows";
    public static final String STRATEGIES = "4-strategies";

    /** Features of FEATURES.md that have no cases, and why. Checked by the structure test. */
    public static final Map<String, String> NOT_COVERED = Map.ofEntries(
            Map.entry("1.3", "steering needs input during a running turn; the case API acts only between turns"),
            Map.entry("1.4", "provider fallback, retries and streaming need a controllable server"),
            Map.entry("1.8", "web tools need the internet or a fetchable local host; host approvals vary by network"),
            Map.entry("1.10", "the classifier needs a separate fast model and a vague-request corpus"),
            Map.entry("1.16", "OAuth logins need an authorization server"),
            Map.entry("1.24", "metrics are not model behavior; usage is read by the cache checks"),
            Map.entry("1.25", "needs identity wiring; low priority in the features document"),
            Map.entry("4.4", "noise pruning is internal to a run; no model-visible behavior to grade yet"));

    /** A smoke case of the feature. */
    public static EvalCase.Builder smoke(String feature, String name) {
        return EvalCase.named(name).feature(feature).tag(EvalCase.Tag.SMOKE);
    }

    /** A full-suite case of the feature. */
    public static EvalCase.Builder full(String feature, String name) {
        return EvalCase.named(name).feature(feature).tag(EvalCase.Tag.FULL);
    }

    // ---- workspace setup ---------------------------------------------------------------------------------------

    /** Writes the given {@code path, content} pairs into the workspace. */
    public static BiConsumer<Path, Map<String, Object>> files(String... pathsAndContents) {
        return (ws, extras) -> {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                try {
                    Path p = ws.resolve(pathsAndContents[i]);
                    if (p.getParent() != null) Files.createDirectories(p.getParent());
                    Files.writeString(p, pathsAndContents[i + 1]);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /** Numbered filler lines; line {@code specialLine} is replaced by {@code special}. */
    public static String lines(String prefix, int count, int specialLine, String special) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(i == specialLine ? special : prefix + " " + i + " status=ok").append('\n');
        }
        return sb.toString();
    }

    /** About {@code chars} characters of neutral filler text with no digits. */
    public static String filler(int chars, String topic) {
        String unit = " module of " + topic + " keeps its routine bookkeeping steady and records nothing unusual. ";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < chars) sb.append(i++ % 2 == 0 ? "This" : "That").append(unit);
        return sb.toString();
    }

    // ---- settings ----------------------------------------------------------------------------------------------

    /** Loads settings built from a JSON object edited by {@code edit}. */
    public static HarnessSettings settings(Consumer<ObjectNode> edit) {
        try {
            ObjectNode root = M.createObjectNode();
            edit.accept(root);
            Path f = Files.createTempFile("jllm-eval-suite", ".json");
            try {
                Files.writeString(f, M.writeValueAsString(root));
                return HarnessSettings.load(f);
            } finally {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A temporary folder that is removed when the JVM exits (when empty; files are removed as they are made). */
    public static Path tempDir(String prefix) {
        try {
            Path d = Files.createTempDirectory(prefix);
            d.toFile().deleteOnExit();
            return d;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a file into a temp folder (deleted on exit) and returns its path. */
    public static Path tempFile(Path dir, String name, String content) {
        try {
            Path f = dir.resolve(name);
            Files.writeString(f, content);
            f.toFile().deleteOnExit();
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The fixtures, then one MCP source of the server with extra fields (allowList, denyList...) in its entry. */
    public static Consumer<AgentHarness.Builder> mcpWith(Fixtures fx, FakeServer server, boolean readOnly,
                                                         Consumer<ObjectNode> entry) {
        return b -> {
            fx.harness().accept(b);
            b.settings(settings(root -> {
                ObjectNode e = root.putArray("mcpServers").addObject();
                e.put("name", server.name()).put("url", server.url()).put("readOnly", readOnly);
                entry.accept(e);
            }));
        };
    }

    // ---- lazily bound grading ----------------------------------------------------------------------------------

    private static synchronized LlmJudge judge() {
        if (judge == null) {
            EvalRun run = EvalRun.get();
            judge = new LlmJudge(EvalModels.judge(), run.config().judge().model(), run.config().selfGraded(),
                    run.config().outputDir().resolve("judge-cache"));
        }
        return judge;
    }

    /** The case graded by the judge on the rubric: names the rubric for the report and adds the judge check. */
    public static EvalCase.Builder judged(EvalCase.Builder b, Rubric rubric) {
        return b.rubric(rubric.name()).check(judge(rubric));
    }

    /** The run's judge on a rubric; resolved when the check runs, so a case builds without a model. */
    public static Check judge(Rubric rubric) {
        return Check.of("judge:" + rubric.name(), Check.Tier.JUDGE, ctx -> judge().judge(ctx, rubric));
    }

    /** Facts in the final answer: exact match first, then the judge for the rest. */
    public static Check retained(RetentionChecks.Fact... facts) {
        Judge lazy = (q, e, a) -> judge().judge(q, e, a);
        return Checks.retained(null, lazy, facts);
    }

    public static RetentionChecks.Fact fact(String question, String expected) {
        return new RetentionChecks.Fact(question, expected);
    }

    public static RetentionChecks.Fact fact(org.kendar.llm.eval.fixture.Fact f) {
        return new RetentionChecks.Fact(f.question(), f.answer());
    }

    // ---- trace checks ------------------------------------------------------------------------------------------

    private static String shorten(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    private static Check trace(String name, Function<TrialContext, CheckResult> f) {
        return Check.of(name, Check.Tier.TRACE, f);
    }

    private static Check state(String name, Function<TrialContext, CheckResult> f) {
        return Check.of(name, Check.Tier.STATE, f);
    }

    /** The last {@code run_finished} outcome is one of the given values. */
    public static Check runOutcome(String... outcomes) {
        String n = "runOutcome" + List.of(outcomes);
        return trace(n, ctx -> {
            List<AgentEvent> ev = ctx.trace().events("run_finished");
            if (ev.isEmpty()) return CheckResult.fail(n, "no run_finished event");
            String o = ev.get(ev.size() - 1).data().path("outcome").asText("");
            return Set.of(outcomes).contains(o) ? CheckResult.pass(n, "outcome " + o)
                    : CheckResult.fail(n, "outcome was " + o);
        });
    }

    /** Some tool result event (cut at 2000 chars) contains the text. */
    public static Check anyToolResultContains(String text) {
        String n = "toolResultContains(" + text + ")";
        return trace(n, ctx -> ctx.trace().events("tool_result").stream()
                .anyMatch(e -> e.data() != null && e.data().path("output").asText("").contains(text))
                ? CheckResult.pass(n, "found") : CheckResult.fail(n, "no tool result contains the text"));
    }

    /** Some tool result event (cut at 2000 chars) matches the regex. */
    public static Check anyToolResultMatches(String regex) {
        String n = "toolResultMatches(" + regex + ")";
        Pattern p = Pattern.compile(regex);
        return trace(n, ctx -> ctx.trace().events("tool_result").stream()
                .anyMatch(e -> e.data() != null && p.matcher(e.data().path("output").asText("")).find())
                ? CheckResult.pass(n, "found") : CheckResult.fail(n, "no tool result matches"));
    }

    /** Some message of a main model request contains the text (what the model really saw). */
    public static Check requestsContain(String text) {
        String n = "requestsContain(" + text + ")";
        String escaped = text.replace("\"", "\\\"");
        return trace(n, ctx -> ctx.trace().mainRequests().stream().anyMatch(r -> r.messages().toString().contains(escaped))
                ? CheckResult.pass(n, "found") : CheckResult.fail(n, "no request carried the text"));
    }

    /** The tool is in the tool list of at least one main request. */
    public static Check toolOffered(String tool) {
        String n = "toolOffered(" + tool + ")";
        return trace(n, ctx -> ctx.trace().mainRequests().stream().anyMatch(r -> r.toolNames().contains(tool))
                ? CheckResult.pass(n, "offered") : CheckResult.fail(n, "never offered"));
    }

    /** The tool is in no main request's tool list. */
    public static Check toolNotOffered(String tool) {
        String n = "toolNotOffered(" + tool + ")";
        return trace(n, ctx -> ctx.trace().mainRequests().stream().noneMatch(r -> r.toolNames().contains(tool))
                ? CheckResult.pass(n, "never offered") : CheckResult.fail(n, "was offered"));
    }

    public static Check anyToolCalled(String... tools) {
        String n = "anyToolCalled" + List.of(tools);
        Set<String> set = Set.of(tools);
        return trace(n, ctx -> ctx.trace().toolCalls().stream().anyMatch(e -> set.contains(TrialTrace.toolName(e)))
                ? CheckResult.pass(n, "called") : CheckResult.fail(n, "none called"));
    }

    public static Check toolCallsAtMost(String tool, int max) {
        String n = "toolCallsAtMost(" + tool + ", " + max + ")";
        return trace(n, ctx -> {
            long c = ctx.trace().toolCalls().stream().filter(e -> tool.equals(TrialTrace.toolName(e))).count();
            return c <= max ? CheckResult.pass(n, c + " call(s)") : CheckResult.fail(n, c + " call(s)");
        });
    }

    public static Check answerNotMatches(String regex) {
        String n = "answerNotMatches(" + regex + ")";
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        return trace(n, ctx -> ctx.finalAnswer() != null && p.matcher(ctx.finalAnswer()).find()
                ? CheckResult.fail(n, "answer matches: " + shorten(ctx.finalAnswer())) : CheckResult.pass(n, "no match"));
    }

    /** Number of main model requests in range, e.g. a slash command must not add one. */
    public static Check mainRequests(int min, int max) {
        String n = "mainRequests(" + min + ".." + max + ")";
        return trace(n, ctx -> {
            int c = ctx.trace().mainRequests().size();
            return c >= min && c <= max ? CheckResult.pass(n, c + " request(s)") : CheckResult.fail(n, c + " request(s)");
        });
    }

    /** Some tool call came from a script: its parent is {@code run_js:<id>}. */
    public static Check innerCallFromScript() {
        String n = "innerCallFromScript";
        return trace(n, ctx -> ctx.trace().toolCalls().stream().anyMatch(e -> e.data() != null
                && e.data().path("parent").asText("").startsWith("run_js:"))
                ? CheckResult.pass(n, "inner call seen") : CheckResult.fail(n, "no call with a run_js parent"));
    }

    /** Every tool result event output is at most {@code max} chars. */
    public static Check eventOutputsAtMost(int max) {
        String n = "eventOutputsAtMost(" + max + ")";
        return trace(n, ctx -> {
            for (AgentEvent e : ctx.trace().events("tool_result")) {
                int len = e.data() == null ? 0 : e.data().path("output").asText("").length();
                if (len > max) return CheckResult.fail(n, "a tool_result event has " + len + " chars");
            }
            return CheckResult.pass(n, "all within the cap");
        });
    }

    // ---- state checks ------------------------------------------------------------------------------------------

    /** Fake server operation calls (tool or API operation, {@code op} null = any) between min and max. */
    public static Check serverOps(String server, String op, int min, int max) {
        String n = "serverOps(" + server + (op == null ? "" : "." + op) + ", " + min + ".." + max + ")";
        return state(n, ctx -> {
            FakeServer s = Fixtures.server(ctx.extras(), server);
            if (s == null) return CheckResult.fail(n, "no server " + server + " in the extras");
            int c = (op == null ? s.operationCalls() : s.operationCalls(op)).size();
            return c >= min && c <= max ? CheckResult.pass(n, c + " call(s)") : CheckResult.fail(n, c + " call(s)");
        });
    }

    /** Executions of a {@link SampleTools} tool between min and max. */
    public static Check toolExecutions(String tool, int min, int max) {
        String n = "toolExecutions(" + tool + ", " + min + ".." + max + ")";
        return state(n, ctx -> {
            SampleTools t = (SampleTools) ctx.extras().get(Fixtures.TOOLS);
            if (t == null) return CheckResult.fail(n, "no sample tools in the extras");
            int c = t.executions(tool);
            return c >= min && c <= max ? CheckResult.pass(n, c + " execution(s)") : CheckResult.fail(n, c + " execution(s)");
        });
    }

    /** The email outbox of the sample tools has between min and max mails. */
    public static Check emailsSent(int min, int max) {
        String n = "emailsSent(" + min + ".." + max + ")";
        return state(n, ctx -> {
            SampleTools t = (SampleTools) ctx.extras().get(Fixtures.TOOLS);
            if (t == null) return CheckResult.fail(n, "no sample tools in the extras");
            int c = t.outbox().size();
            return c >= min && c <= max ? CheckResult.pass(n, c + " mail(s)") : CheckResult.fail(n, c + " mail(s)");
        });
    }

    /** The file exists in the workspace (any content). */
    public static Check fileExists(String path) {
        return Checks.fileMatches(path, s -> true, "exists");
    }

    /** The file has exactly {@code count} occurrences of {@code text}. */
    public static Check fileHasCount(String path, String text, int count) {
        return Checks.fileMatches(path, s -> (s.length() - s.replace(text, "").length()) / text.length() == count,
                count + " x '" + text + "'");
    }

    // ---- strategy sessions -------------------------------------------------------------------------------------

    /**
     * The strategy a trial ran under, read from the request: mnemonic has history_recall, scope todo_write and its
     * system prompt section (with {@code context.todos} any strategy has todo_write).
     */
    public static ContextStrategyKind detectStrategy(List<RecordedRequest> mainRequests) {
        if (mainRequests.isEmpty()) return null;
        RecordedRequest first = mainRequests.get(0);
        List<String> tools = first.toolNames();
        if (tools.contains("history_recall")) return ContextStrategyKind.MNEMONIC;
        String scope = org.kendar.llm.harness.context.scope.ScopeStrategy.systemAddendum();
        if (tools.contains("todo_write") && (String.valueOf(first.extraSystem()).contains(scope)
                || String.valueOf(first.pinnedSystem()).contains(scope))) {
            return ContextStrategyKind.SCOPE;
        }
        return ContextStrategyKind.COMPACTION;
    }

    /** Applies the check only when the trial ran under {@code kind}; passes with a note otherwise. */
    public static Check onlyFor(ContextStrategyKind kind, Check inner) {
        return Check.of(inner.name() + "@" + kind.name().toLowerCase(), inner.tier(), ctx -> {
            ContextStrategyKind actual = detectStrategy(ctx.trace().mainRequests());
            if (actual != kind) return CheckResult.pass(inner.name(), "not applicable to " + actual);
            return inner.check(ctx);
        });
    }

    /** The cases with the given tag. */
    public static List<EvalCase> withTag(List<EvalCase> all, EvalCase.Tag tag) {
        return all.stream().filter(c -> c.tag() == tag).toList();
    }
}

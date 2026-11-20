package org.kendar.llm.eval.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.eval.grade.CheckResult;
import org.kendar.llm.eval.run.TrialResult;
import org.kendar.llm.eval.run.TrialRunner;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.testsupport.HarnessTestSupport;
import org.kendar.llm.harness.testsupport.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.testsupport.HarnessTestSupport.text;

@Tag("unit")
class CaseApiTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path out;

    private static ModelResponse toolUse(String id, String name) {
        ArrayNode c = M.createArrayNode();
        c.addObject().put("type", "tool_use").put("id", id).put("name", name).set("input", M.createObjectNode());
        return new ModelResponse(c, "tool_use", new Usage(10, 5, 0, 0));
    }

    private static Check answerIs(String expected) {
        return Check.of("answer", Check.Tier.TRACE, ctx -> ctx.finalAnswer().equals(expected)
                ? CheckResult.pass("answer", "ok") : CheckResult.fail("answer", "got " + ctx.finalAnswer()));
    }

    private TrialRunner runner(ScriptedModel model) {
        return new TrialRunner(model, null, new TrialRunner.Settings(32_000, false));
    }

    @Test
    void expandsByStrategyOnly() {
        EvalCase c = EvalCase.named("x").user("hi")
                .strategies(ContextStrategyKind.COMPACTION, ContextStrategyKind.MNEMONIC, ContextStrategyKind.SCOPE)
                .build();
        assertEquals(List.of("general/x [compaction]", "general/x [mnemonic]", "general/x [scope]"),
                CaseExpander.displayNames(c));
        assertEquals(3, CaseExpander.expand(List.of(c), k -> null).count());
    }

    @Test
    void defaultsToCompactionAndRejectsBadCases() {
        assertEquals(List.of(ContextStrategyKind.COMPACTION), EvalCase.named("x").user("a").build().strategies());
        assertThrows(IllegalStateException.class, () -> EvalCase.named("x").build());
        assertThrows(IllegalStateException.class, () -> EvalCase.named("x").user("a")
                .strategies(ContextStrategyKind.SCOPE, ContextStrategyKind.SCOPE).build());
        assertThrows(IllegalArgumentException.class, () -> EvalCase.named("x").trials(0));
    }

    private static TrialResult trial(int n, boolean ok) {
        return new TrialResult(n, null, List.of(ok ? CheckResult.pass("a", "") : CheckResult.fail("a", "bad")), ok,
                Path.of("t" + n));
    }

    @Test
    void thresholdsAndAggregation() {
        EvalCase normal = EvalCase.named("n").user("a").build();
        EvalCase risk = EvalCase.named("r").user("a").risk().build();
        List<TrialResult> twoOfThree = List.of(trial(1, true), trial(2, false), trial(3, true));

        CaseResult n = CaseResult.of(normal, "compaction", twoOfThree);
        assertEquals(2.0 / 3, n.passRate(), 1e-9);
        assertFalse(n.passK());
        assertTrue(n.passed());

        CaseResult r = CaseResult.of(risk, "compaction", twoOfThree);
        assertFalse(r.passed(), "risk needs every trial");
        assertTrue(r.failureMessage().contains("trial 2") && r.failureMessage().contains("t2"));

        assertTrue(CaseResult.of(risk, "compaction", List.of(trial(1, true), trial(2, true))).passK());
        assertFalse(CaseResult.of(normal, "compaction", List.of(trial(1, false), trial(2, false), trial(3, true)))
                .passed());
        assertFalse(CaseResult.of(normal, "compaction", List.of()).passed());
    }

    @Test
    void runnerDrivesHarnessWithScriptedModelAndGradesCheapestFirst() {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(toolUse("t1", "ping"), text("pong done"));
        List<String> order = new ArrayList<>();
        EvalCase c = EvalCase.named("ping").user("ping please")
                .harness(b -> b.withoutStandardTools().tools(List.of(HarnessTestSupport.tool("ping", "Misc",
                        ToolKind.READ_ONLY, (ctx, in) -> "pong"))))
                .check(Check.of("late", Check.Tier.JUDGE, ctx -> {
                    order.add("late");
                    return CheckResult.pass("late", "");
                }))
                .check(Check.of("early", Check.Tier.TRACE, ctx -> {
                    order.add("early");
                    return ctx.trace().toolCalls().size() == 1 ? CheckResult.pass("early", "one call")
                            : CheckResult.fail("early", "calls=" + ctx.trace().toolCalls().size());
                }))
                .check(answerIs("pong done"))
                .build();

        TrialResult r = runner(model).run(c, ContextStrategyKind.COMPACTION, 1, out.resolve("tr"));

        assertTrue(r.passed(), r.checks().toString());
        assertEquals("early", order.get(0));
        assertEquals(2, r.trace().mainRequests().size());
        assertEquals("pong done", r.trace().finalAnswer());
        assertTrue(Files.exists(r.traceFile()));
    }

    @Test
    void theSystemPromptExtraOfTheRunReachesTheModel() {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("ok"));
        EvalCase c = EvalCase.named("extra").user("hi").check(answerIs("ok")).build();

        new TrialRunner(model, null, new TrialRunner.Settings(32_000, false, 0, "TOOLS-AS-JSON-ONLY"))
                .run(c, ContextStrategyKind.COMPACTION, 1, out.resolve("tr"));

        assertTrue(model.requests.get(0).extra().contains("TOOLS-AS-JSON-ONLY"), model.requests.get(0).extra());
    }

    @Test
    void noExtraMeansTheHarnessPromptIsUntouched() {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("ok"));
        EvalCase c = EvalCase.named("plain").user("hi").check(answerIs("ok")).build();

        runner(model).run(c, ContextStrategyKind.COMPACTION, 1, out.resolve("tr"));

        assertFalse(model.requests.get(0).extra().contains("TOOLS-AS-JSON-ONLY"));
    }

    @Test
    void failingCheckStopsGradingAndMessageNamesTrace() {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("wrong"));
        List<String> ran = new ArrayList<>();
        EvalCase c = EvalCase.named("f").user("q").trials(1)
                .check(answerIs("right"))
                .check(Check.of("judge", Check.Tier.JUDGE, ctx -> {
                    ran.add("judge");
                    return CheckResult.pass("judge", "");
                })).build();

        CaseExecutor ex = new CaseExecutor(runner(model), out, 3, null);
        CaseResult res = ex.run(c, ContextStrategyKind.COMPACTION);

        assertFalse(res.passed());
        assertTrue(ran.isEmpty(), "judge must not run after a trace failure");
        assertTrue(res.failureMessage().contains("trial-1.json"));
        assertTrue(Files.exists(out.resolve("general/f/compaction/result.json")));
    }

    @Test
    void executorRunsConfiguredTrialsAndRiskStopsAtFirstFailure() {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 6; i++) model.enqueue(text("ok"));
        EvalCase normal = EvalCase.named("n").user("q").check(answerIs("ok")).build();
        CaseExecutor ex = new CaseExecutor(runner(model), out, 3, null);
        assertEquals(3, ex.run(normal, ContextStrategyKind.COMPACTION).trials().size());

        ScriptedModel bad = new ScriptedModel();
        for (int i = 0; i < 3; i++) bad.enqueue(text("no"));
        EvalCase risk = EvalCase.named("r").user("q").risk().check(answerIs("ok")).build();
        CaseResult r = new CaseExecutor(runner(bad), out, 3, null).run(risk, ContextStrategyKind.COMPACTION);
        assertEquals(1, r.trials().size());
        assertFalse(r.passed());
    }

    @Test
    void modelFailureBecomesFailedTrialNotException() {
        ScriptedModel model = new ScriptedModel(); // empty queue: the model call throws
        EvalCase c = EvalCase.named("e").user("q").trials(1).build();
        TrialResult r = runner(model).run(c, ContextStrategyKind.COMPACTION, 1, out.resolve("e"));
        assertFalse(r.passed());
    }
}

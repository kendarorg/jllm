package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.fixture.SampleTools;

import java.util.List;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 1.13 Stuck detection and loop robustness. */
class StuckCasesTest extends SuiteBase {

    private static Fixtures failing() {
        return Fixtures.create().sampleTools(new SampleTools(1000));
    }

    @Override
    public List<EvalCase> cases() {
        Fixtures a = failing(), b = failing(), c = Fixtures.create().sampleTools(new SampleTools(1)), e = failing(),
                r = failing();
        return List.of(
                smoke(STUCK, "always-failing-tool-ends")
                        .harness(a.harness()).setup(a.setup())
                        .user("Look up key z with flaky_lookup. If it keeps failing, stop and tell me.")
                        .check(answerNotContains("value-of-z")).check(maxToolCalls(12))
                        .check(runOutcome("completed", "stuck")).build(),
                full(STUCK, "retry-loop-triggers-detector")
                        .harness(b.harness()).setup(b.setup())
                        .approve(req -> !"stuck".equals(req.path("type").asText()))
                        .user("Call flaky_lookup with key q again and again until it succeeds, up to 20 times.")
                        .check(eventPresent("stuck")).check(maxToolCalls(20)).build(),
                full(STUCK, "edit-then-read-is-not-a-loop")
                        .harness(c.harness()).setup(c.setup())
                        .user("Create a file named a.txt containing 1, then read it back and confirm the content.")
                        .check(fileContains("a.txt", "1")).check(eventAbsent("stuck")).build(),
                full(STUCK, "edge-nonexistent-tool-name")
                        .user("Call the tool named super_tool_9000 with no arguments and report the result.")
                        .check(maxToolCalls(6)).check(runOutcome("completed", "stuck")).build(),
                full(STUCK, "edge-stuck-question-declined")
                        .harness(e.harness()).setup(e.setup())
                        .approve(req -> !"stuck".equals(req.path("type").asText()))
                        .user("Keep calling flaky_lookup with key w until it works. Do not give up.")
                        .check(maxToolCalls(20)).check(runOutcome("completed", "stuck")).check(stuckRunAnswers())
                        .build(),
                full(STUCK, "risk-no-endless-identical-calls")
                        .harness(r.harness()).setup(r.setup())
                        .approve(req -> !"stuck".equals(req.path("type").asText()))
                        .user("Look up key k with flaky_lookup and tell me the value.")
                        .check(maxDuplicateCalls(5)).check(maxToolCalls(15)).check(answerNotContains("value-of-k"))
                        .risk().build());
    }
}

package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Fact;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.fixture.LargeOutput;
import org.kendar.llm.harness.HarnessConfig;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 1.21 Output limits and truncation. */
class OutputLimitsCasesTest extends SuiteBase {

    private static UnaryOperator<HarnessConfig> toolMaxChars(int max) {
        return c -> c.withOutput(new HarnessConfig.Output(max, c.output().eventMaxChars(), c.output().sanitize(),
                c.output().taintGating(), c.output().extraPatterns(), c.output().validation()));
    }

    @Override
    public List<EvalCase> cases() {
        LargeOutput fits = new LargeOutput(300, 3, 11), tight = new LargeOutput(300, 3, 12),
                head = new LargeOutput(60, 1, 13), tail = new LargeOutput(2000, 3, 14);
        Fixtures a = Fixtures.create().largeOutput(fits), b = Fixtures.create().largeOutput(tight),
                e1 = Fixtures.create().largeOutput(head), e2 = Fixtures.create().largeOutput(tail);
        Fixtures c = Fixtures.create().largeOutput(new LargeOutput(300, 3, 15));
        Fact f0 = fits.facts().get(0), f1 = fits.facts().get(1), f2 = fits.facts().get(2);
        Fact middle = tail.facts().get(1);
        return List.of(
                smoke(LIMITS, "report-within-limit-answerable").harness(a.harness()).setup(a.setup())
                        .user("Call big_report once and answer: " + f0.question() + " " + f1.question() + " " + f2.question())
                        .check(toolCalled("big_report")).check(answerContains(f0.answer())).check(answerContains(f1.answer()))
                        .check(answerContains(f2.answer())).build(),
                full(LIMITS, "truncation-marker-reaches-model").harness(b.harness()).setup(b.setup())
                        .config(toolMaxChars(2000))
                        .user("Call big_report and tell me how many lines it has.")
                        .check(toolCalled("big_report")).check(requestsContain("[truncated")).build(),
                full(LIMITS, "event-output-is-capped").harness(c.harness()).setup(c.setup())
                        .user("Call big_report and tell me the first line of it.")
                        .check(toolCalled("big_report")).check(eventOutputsAtMost(2100)).build(),
                full(LIMITS, "edge-fact-in-kept-head").harness(e1.harness()).setup(e1.setup())
                        .config(toolMaxChars(3000))
                        .user("Call big_report and answer: " + head.facts().get(0).question())
                        .check(answerContains(head.facts().get(0).answer())).build(),
                full(LIMITS, "edge-fact-beyond-cut-not-invented").harness(e2.harness()).setup(e2.setup())
                        .user("Call big_report and answer: " + middle.question() + " If the output was cut and you cannot see it, say so.")
                        .check(answerNotContains(middle.answer())).check(maxToolCalls(4))
                        .check(answerMatches("(?i)(truncat|cut|incomplete|not (see|find|available|visible)|unable|cannot|can't|couldn't)"))
                        .build(),
                full(LIMITS, "risk-log-tail-beyond-read-cap")
                        .setup(files("errors.log", lines("event worker-3 ok latency=12ms payload=abcdefghijklmnopqrstuvwxyz", 1000, 1000,
                                "FATAL: disk quota exceeded for tenant ACME-5521")))
                        .user("errors.log is long. What is the final error at the end of errors.log? Quote the tenant it names.")
                        .check(answerContains("ACME-5521")).risk().build());
    }
}

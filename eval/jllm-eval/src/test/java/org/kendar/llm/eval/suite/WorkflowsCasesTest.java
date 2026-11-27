package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.fixture.Fixtures;
import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.eval.grade.CheckResult;

import java.util.List;
import java.util.function.Predicate;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/**
 * 2.4 workflows engine: the sample flows are started by chat commands (deterministic, no model); the user turn only
 * proves the chat still works afterwards. Flows are never offered as tools.
 */
class WorkflowsCasesTest extends SuiteBase {

    private static Check executions(String name, Fixtures f, Predicate<List<String>> test, String description) {
        return Check.of(name, Check.Tier.STATE, ctx -> {
            List<String> ex = f.flowExecutions();
            return test.test(ex) ? CheckResult.pass(name, description + ": " + ex)
                    : CheckResult.fail(name, "expected " + description + " but ran " + ex);
        });
    }

    @Override
    public List<EvalCase> cases() {
        Fixtures a = Fixtures.create().workflows(), b = Fixtures.create().workflows(), c = Fixtures.create().workflows();
        Fixtures d = Fixtures.create().workflows();
        return List.of(
                smoke(WORKFLOWS, "linear-flow-runs-actions")
                        .harness(a.harness()).setup(a.setup())
                        .action("/flow-start linear", (h, chat) -> h.commands().execute(chat,
                                "/flow-start linear {\"text\":\"hello flow\"}"))
                        .user("Reply with the single word PONG.")
                        .check(executions("linearRan", a, ex -> ex.contains("normalizeText") && ex.contains("countWords"),
                                "normalizeText and countWords"))
                        .check(answerContains("PONG")).check(toolNotOffered("linear")).build(),
                full(WORKFLOWS, "branching-flow-takes-large-branch")
                        .harness(b.harness()).setup(b.setup())
                        .action("/flow-start branching", (h, chat) -> h.commands().execute(chat,
                                "/flow-start branching {\"amount\":500}"))
                        .user("Reply with the single word PONG.")
                        .check(executions("largeBranch", b, ex -> ex.contains("amountBand:large") && ex.contains("flagReview")
                                && !ex.contains("approve"), "large band, flagReview, no approve"))
                        .check(answerContains("PONG")).build(),
                full(WORKFLOWS, "gate-reads-boolean-not-prose")
                        .harness(d.harness()).setup(d.setup())
                        .action("/flow-start gated", (h, chat) -> h.commands().execute(chat,
                                "/flow-start gated {\"approved\":\"APPROVED\"}"))
                        .user("Reply with the single word PONG.")
                        .check(executions("gateFellBack", d, ex -> ex.contains("flagReview") && !ex.contains("approve"),
                                "flagReview, no approve (text is not a boolean)"))
                        .check(answerContains("PONG")).build(),
                full(WORKFLOWS, "failure-unknown-flow-runs-nothing")
                        .harness(c.harness()).setup(c.setup())
                        .action("/flow-start nosuchflow", (h, chat) -> h.commands().execute(chat, "/flow-start nosuchflow"))
                        .user("Reply with the single word PONG.")
                        .check(executions("nothingRan", c, List::isEmpty, "no action executed"))
                        .check(answerContains("PONG")).build());
    }
}

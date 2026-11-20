package org.kendar.llm.eval.suite.examples;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.kendar.llm.eval.cases.CaseExpander;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.suite.Suite;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.permission.PermissionMode;

import java.util.List;
import java.util.stream.Stream;

import static org.kendar.llm.eval.grade.Checks.*;

/**
 * Example 2: a short session replayed under every context strategy. It plants a fact, switches the permission mode,
 * reads a large file, forces a compaction with /compact (there must be enough to summarize: a tiny conversation is left
 * alone) and asks for the fact at the end (retention check).
 */
public class ExampleStrategyEvalTest {

    public static List<EvalCase> cases() {
        EvalCase c = EvalCase.named("fact-survives-compaction").feature("examples").tag(EvalCase.Tag.STRATEGY)
                .strategies(ContextStrategyKind.COMPACTION, ContextStrategyKind.MNEMONIC, ContextStrategyKind.SCOPE)
                .contextBudgetTokens(40_000) // never reached: the compaction below is forced, at the same point on every run
                .setup(Suite.files("big.txt", Suite.filler(24_000, "archive")))
                .user("Remember this: the booking reference is 7391-KITE. Acknowledge in one short sentence.")
                .modeSwitch(PermissionMode.EDIT)
                .user("Read big.txt completely and tell me how many lines it has.")
                .user("Thanks. Reply with just: OK") // /compact keeps the latest turn: the big read must not be it
                .action("/compact", (h, chat) -> h.commands().execute(chat, "/compact"))
                .user("Without reading anything again: what is the booking reference?")
                .check(noRunError())
                .check(eventPresent("compaction"))
                .check(Suite.retained(Suite.fact("What is the booking reference?", "7391-KITE")))
                .check(Suite.onlyFor(ContextStrategyKind.COMPACTION, compactionSummaryHasSections()))
                .check(Suite.onlyFor(ContextStrategyKind.MNEMONIC, mnemonicLogNeverRewritten()))
                .trials(1)
                .build();
        return List.of(c);
    }

    @Tag("strategy")
    @TestFactory
    Stream<DynamicNode> strategy() {
        return CaseExpander.expand(cases());
    }
}

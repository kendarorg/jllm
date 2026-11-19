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
 * Example 2: a short session replayed under every context strategy with a small budget. It plants a fact, switches the
 * permission mode, forces compaction with a large file and asks for the fact at the end (retention check).
 */
public class ExampleStrategyEvalTest {

    public static List<EvalCase> cases() {
        EvalCase c = EvalCase.named("fact-survives-compaction").feature("examples").tag(EvalCase.Tag.STRATEGY)
                .strategies(ContextStrategyKind.COMPACTION, ContextStrategyKind.MNEMONIC, ContextStrategyKind.SCOPE)
                .contextBudgetTokens(8_000)
                .setup(Suite.files("big.txt", Suite.filler(24_000, "archive")))
                .user("Remember this: the vault code is 7391-KITE. Acknowledge in one short sentence.")
                .action("/compact", (h, chat) -> h.commands().execute(chat, "/compact"))
                .modeSwitch(PermissionMode.EDIT)
                .user("Read big.txt completely and tell me how many lines it has.")
                .user("Without reading anything again: what is the vault code?")
                .check(noRunError())
                .check(eventPresent("compaction"))
                .check(Suite.retained(Suite.fact("What is the vault code?", "7391-KITE")))
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

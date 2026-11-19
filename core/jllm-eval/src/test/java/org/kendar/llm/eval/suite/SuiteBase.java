package org.kendar.llm.eval.suite;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.kendar.llm.eval.cases.CaseExpander;
import org.kendar.llm.eval.cases.EvalCase;

import java.util.List;
import java.util.stream.Stream;

/**
 * Base of the generated case classes: {@link #cases()} defines every case of the area; the three factories run them
 * by tag, so smoke, full and strategy runs select by JUnit group.
 */
public abstract class SuiteBase {

    /** All cases of the area, each with its tag. Must not need a model. */
    public abstract List<EvalCase> cases();

    @Tag("smoke")
    @TestFactory
    Stream<DynamicNode> smokeCases() {
        return CaseExpander.expand(Suite.withTag(cases(), EvalCase.Tag.SMOKE));
    }

    @Tag("full")
    @TestFactory
    Stream<DynamicNode> fullCases() {
        return CaseExpander.expand(Suite.withTag(cases(), EvalCase.Tag.FULL));
    }

    @Tag("strategy")
    @TestFactory
    Stream<DynamicNode> strategyCases() {
        return CaseExpander.expand(Suite.withTag(cases(), EvalCase.Tag.STRATEGY));
    }

    @Tag("compaction")
    @TestFactory
    Stream<DynamicNode> compactionCases() {
        return CaseExpander.expand(Suite.withTag(cases(), EvalCase.Tag.COMPACTION));
    }
}

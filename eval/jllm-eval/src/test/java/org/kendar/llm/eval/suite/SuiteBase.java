package org.kendar.llm.eval.suite;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.kendar.llm.eval.cases.CaseExpander;
import org.kendar.llm.eval.cases.EvalCase;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Base of the generated case classes: {@link #cases()} defines every case of the area; the factories run them
 * by tag, so smoke, full, strategy and compaction runs select by JUnit group.
 */
public abstract class SuiteBase {

    /** Factory method name to the tag of the cases it runs. */
    static final Map<String, EvalCase.Tag> FACTORIES = Map.of(
            "smokeCases", EvalCase.Tag.SMOKE,
            "fullCases", EvalCase.Tag.FULL,
            "strategyCases", EvalCase.Tag.STRATEGY,
            "compactionCases", EvalCase.Tag.COMPACTION);

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

    /**
     * Number of dynamic tests the factory {@code method} of {@code area} produces (one per case and strategy);
     * 0 when the method is not one of the factories.
     */
    static int plannedTests(Class<? extends SuiteBase> area, String method) throws ReflectiveOperationException {
        EvalCase.Tag tag = FACTORIES.get(method);
        if (tag == null) return 0;
        Constructor<? extends SuiteBase> ctor = area.getDeclaredConstructor();
        ctor.setAccessible(true);
        return Suite.withTag(ctor.newInstance().cases(), tag).stream().mapToInt(c -> c.strategies().size()).sum();
    }
}

package org.kendar.llm.eval.cases;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.kendar.llm.eval.EvalModels;
import org.kendar.llm.eval.EvalRun;
import org.kendar.llm.eval.run.TrialRunner;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The JUnit side of the cases: expands each case into one dynamic test per strategy (the model is fixed for the run)
 * and fails a test with the failed checks and the trace location. Use from a {@code @TestFactory}:
 *
 * <pre>{@code
 * @Tag("smoke") @TestFactory Stream<DynamicNode> cases() { return CaseExpander.expand(MyCases.all()); }
 * }</pre>
 */
public final class CaseExpander {

    private CaseExpander() {
    }

    /** Expands against the real run (connection check, target, judge...). Tests are lazily bound to the run. */
    public static Stream<DynamicNode> expand(List<EvalCase> cases) {
        return expand(cases, lazyExecutor());
    }

    /** Expands with a given executor provider; tests use this with a scripted model. */
    public static Stream<DynamicNode> expand(List<EvalCase> cases, Function<EvalCase, CaseExecutor> executors) {
        return cases.stream().flatMap(c -> c.strategies().stream().map(s -> (DynamicNode) DynamicTest
                .dynamicTest(displayName(c, s), () -> execute(executors.apply(c), c, s))));
    }

    /** Names of the tests {@link #expand} produces for a case, in order. */
    public static List<String> displayNames(EvalCase c) {
        return c.strategies().stream().map(s -> displayName(c, s)).toList();
    }

    static String displayName(EvalCase c, ContextStrategyKind s) {
        return c.feature() + "/" + c.name() + " [" + s.name().toLowerCase() + "]";
    }

    static void execute(CaseExecutor ex, EvalCase c, ContextStrategyKind s) {
        CaseResult r = ex.run(c, s);
        if (!r.passed()) throw new AssertionFailedError(r.failureMessage());
    }

    private static Function<EvalCase, CaseExecutor> lazyExecutor() {
        return c -> {
            EvalRun run = EvalRun.get();
            TrialRunner runner = new TrialRunner(EvalModels.target(),
                    run.config().fastSeparate() ? EvalModels.fast() : null,
                    new TrialRunner.Settings(run.config().target().contextTokens(),
                            run.config().target().smallModel(), run.config().caseTimeoutSeconds()));
            return new CaseExecutor(runner, run.outputDir(), run.config().trials(), run.config().maxTrials(),
                    run.metadata());
        };
    }
}

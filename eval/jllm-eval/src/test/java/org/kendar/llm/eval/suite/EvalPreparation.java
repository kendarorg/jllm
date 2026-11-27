package org.kendar.llm.eval.suite;

import org.junit.platform.launcher.TestPlan;

/**
 * Work an eval module does when the test plan starts, before {@link EvalProgressListener} counts the cases (e.g.
 * downloading the data the cases are built from). Implementations are found with {@link java.util.ServiceLoader}
 * through {@code META-INF/services/org.kendar.llm.eval.suite.EvalPreparation}.
 */
public interface EvalPreparation {

    /** Prepares what the selected tests of {@code plan} need; does nothing when none of them is its own. */
    void prepare(TestPlan plan);
}

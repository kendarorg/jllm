package org.kendar.llm.eval.run;

import org.kendar.llm.eval.grade.CheckResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of one graded trial.
 *
 * @param checks    results of the checks that ran, cheapest first, ending at the first failure
 * @param traceFile where the trace was saved, or null
 */
public record TrialResult(int trial, TrialTrace trace, List<CheckResult> checks, boolean passed, Path traceFile) {

    public List<CheckResult> failedChecks() {
        return checks.stream().filter(CheckResult::failed).toList();
    }
}

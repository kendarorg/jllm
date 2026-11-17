package org.kendar.llm.eval.cases;

import org.kendar.llm.eval.run.TrialResult;

import java.util.List;

/**
 * Aggregate of the trials of one case under one strategy.
 *
 * @param passRate fraction of passed trials
 * @param passK    true when all k trials passed (pass^k)
 * @param passed   verdict after the thresholds: all trials for risk cases, else passRate at least the case minimum
 */
public record CaseResult(String caseName, String feature, String strategy, boolean risk, List<TrialResult> trials,
                         double passRate, boolean passK, boolean passed, double minPassRate) {

    public static CaseResult of(EvalCase c, String strategy, List<TrialResult> trials) {
        long ok = trials.stream().filter(TrialResult::passed).count();
        double rate = trials.isEmpty() ? 0 : (double) ok / trials.size();
        boolean all = !trials.isEmpty() && ok == trials.size();
        boolean passed = c.risk() ? all : !trials.isEmpty() && rate + 1e-9 >= c.minPassRate();
        return new CaseResult(c.name(), c.feature(), strategy, c.risk(), List.copyOf(trials), rate, all, passed,
                c.minPassRate());
    }

    /** Failure text: the failed checks of each failed trial and where its trace is saved. */
    public String failureMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(caseName).append(" [").append(strategy).append("] ")
                .append(risk ? "risk case needs every trial to pass" : "needs pass rate " + minPassRate)
                .append(", got ").append(String.format("%.2f", passRate)).append(" over ").append(trials.size())
                .append(" trials");
        for (TrialResult t : trials) {
            if (t.passed()) continue;
            sb.append("\n  trial ").append(t.trial()).append(": ");
            t.failedChecks().forEach(f -> sb.append(f.name()).append(" (").append(f.reason()).append("); "));
            sb.append("trace: ").append(t.traceFile());
        }
        return sb.toString();
    }
}

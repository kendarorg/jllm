package org.kendar.llm.eval.cases;

import org.kendar.llm.eval.report.PassAtK;
import org.kendar.llm.eval.run.TrialResult;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregate of the trials of one case under one strategy.
 *
 * @param passRate  fraction of passed trials (equals pass@1 and pass^1)
 * @param passAtK   pass@k by k, only for the configured k that do not exceed the trials run
 * @param passPowK  pass^k by k, same keys as {@code passAtK}
 * @param passed    verdict after the thresholds: all trials for risk cases, else passRate at least the case minimum
 */
public record CaseResult(String caseName, String feature, String strategy, boolean risk, List<TrialResult> trials,
                         double passRate, Map<Integer, Double> passAtK, Map<Integer, Double> passPowK, boolean passed,
                         double minPassRate) {

    /** @param ks the configured k values ({@code eval.passK}) */
    public static CaseResult of(EvalCase c, String strategy, List<TrialResult> trials, List<Integer> ks) {
        int n = trials.size();
        int ok = (int) trials.stream().filter(TrialResult::passed).count();
        double rate = n == 0 ? 0 : (double) ok / n;
        boolean all = n > 0 && ok == n;
        boolean passed = c.risk() ? all : n > 0 && rate + 1e-9 >= c.minPassRate();
        Map<Integer, Double> at = new TreeMap<>();
        Map<Integer, Double> pow = new TreeMap<>();
        for (int k : ks) {
            PassAtK.passAt(n, ok, k).ifPresent(v -> at.put(k, v));
            PassAtK.passPow(n, ok, k).ifPresent(v -> pow.put(k, v));
        }
        return new CaseResult(c.name(), c.feature(), strategy, c.risk(), List.copyOf(trials), rate,
                java.util.Collections.unmodifiableMap(at), java.util.Collections.unmodifiableMap(pow), passed, c.minPassRate());
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

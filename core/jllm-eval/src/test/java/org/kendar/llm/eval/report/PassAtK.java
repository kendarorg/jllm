package org.kendar.llm.eval.report;

import java.util.OptionalDouble;

/**
 * Unbiased estimators over {@code n} trials of which {@code c} passed, for a sample of {@code k} trials:
 * <ul>
 *   <li>pass@k: chance that at least one of k trials passes, one minus the share of k-subsets with no success;</li>
 *   <li>pass^k: chance that all k trials pass, the share of k-subsets with only successes.</li>
 * </ul>
 * Both are computed as running products, never with factorials, so n in the hundreds cannot overflow. Both are
 * undefined (empty) when k exceeds n: the data cannot tell. For k of 1 both equal the plain pass rate c/n.
 */
public final class PassAtK {

    private PassAtK() {
    }

    /** pass@k, or empty when {@code k > n}. */
    public static OptionalDouble passAt(int n, int c, int k) {
        check(n, c, k);
        if (k > n) return OptionalDouble.empty();
        if (n - c < k) return OptionalDouble.of(1.0);
        // chance that a k-subset holds no success: product of (n-c-i)/(n-i)
        double none = 1.0;
        for (int i = 0; i < k; i++) none *= (double) (n - c - i) / (n - i);
        return OptionalDouble.of(1.0 - none);
    }

    /** pass^k, or empty when {@code k > n}. */
    public static OptionalDouble passPow(int n, int c, int k) {
        check(n, c, k);
        if (k > n) return OptionalDouble.empty();
        if (c < k) return OptionalDouble.of(0.0);
        // chance that a k-subset holds only successes: product of (c-i)/(n-i)
        double all = 1.0;
        for (int i = 0; i < k; i++) all *= (double) (c - i) / (n - i);
        return OptionalDouble.of(all);
    }

    private static void check(int n, int c, int k) {
        if (k < 1) throw new IllegalArgumentException("k must be at least 1 (got " + k + ")");
        if (n < 0 || c < 0 || c > n) {
            throw new IllegalArgumentException("need 0 <= c <= n (got c=" + c + ", n=" + n + ")");
        }
    }
}

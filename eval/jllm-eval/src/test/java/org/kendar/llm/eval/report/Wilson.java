package org.kendar.llm.eval.report;

/** Wilson score interval of a pass rate; used to tell a real change from trial-to-trial noise. */
public final class Wilson {

    /** z of a two-sided 95% interval. */
    public static final double Z95 = 1.96;

    private Wilson() {
    }

    /** Lower bound of the interval for {@code passes} of {@code n}; 0 when {@code n} is 0. */
    public static double low(int passes, int n) {
        return bounds(passes, n)[0];
    }

    /** Upper bound of the interval for {@code passes} of {@code n}; 1 when {@code n} is 0. */
    public static double high(int passes, int n) {
        return bounds(passes, n)[1];
    }

    /** {low, high}; the whole range {0, 1} when there are no trials. */
    public static double[] bounds(int passes, int n) {
        if (n <= 0) return new double[]{0, 1};
        double p = (double) passes / n;
        double z2 = Z95 * Z95;
        double denom = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denom;
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    /** True when the intervals of the two samples do not overlap, i.e. the change is not explained by noise. */
    public static boolean separated(int passesA, int nA, int passesB, int nB) {
        double[] a = bounds(passesA, nA);
        double[] b = bounds(passesB, nB);
        return a[1] < b[0] || b[1] < a[0];
    }
}

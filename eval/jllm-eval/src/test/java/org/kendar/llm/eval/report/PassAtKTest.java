package org.kendar.llm.eval.report;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PassAtKTest {

    private static final double EPS = 1e-12;

    @Test
    void knownValuesForFiveTrialsOfWhichTwoPassed() {
        assertEquals(0.4, PassAtK.passAt(5, 2, 1).getAsDouble(), EPS);
        assertEquals(0.4, PassAtK.passPow(5, 2, 1).getAsDouble(), EPS);
        assertEquals(0.7, PassAtK.passAt(5, 2, 2).getAsDouble(), EPS);
        assertEquals(0.1, PassAtK.passPow(5, 2, 2).getAsDouble(), EPS);
        assertEquals(1.0, PassAtK.passAt(5, 2, 4).getAsDouble(), EPS, "4 of 5 cannot all miss when 2 passed");
        assertEquals(0.0, PassAtK.passPow(5, 2, 3).getAsDouble(), EPS);
    }

    @Test
    void noSuccessAndOnlySuccesses() {
        for (int k = 1; k <= 5; k++) {
            assertEquals(0.0, PassAtK.passAt(5, 0, k).getAsDouble(), EPS);
            assertEquals(0.0, PassAtK.passPow(5, 0, k).getAsDouble(), EPS);
            assertEquals(1.0, PassAtK.passAt(5, 5, k).getAsDouble(), EPS);
            assertEquals(1.0, PassAtK.passPow(5, 5, k).getAsDouble(), EPS);
        }
    }

    @Test
    void kAboveTheTrialsIsUndefined() {
        assertFalse(PassAtK.passAt(5, 2, 6).isPresent());
        assertFalse(PassAtK.passPow(5, 2, 6).isPresent());
        assertFalse(PassAtK.passAt(0, 0, 1).isPresent());
        assertTrue(PassAtK.passAt(5, 2, 5).isPresent());
    }

    @Test
    void kOfOneIsThePlainPassRate() {
        for (int n = 1; n <= 12; n++) {
            for (int c = 0; c <= n; c++) {
                assertEquals((double) c / n, PassAtK.passAt(n, c, 1).getAsDouble(), EPS);
                assertEquals((double) c / n, PassAtK.passPow(n, c, 1).getAsDouble(), EPS);
            }
        }
    }

    @Test
    void largeSamplesDoNotOverflow() {
        double at = PassAtK.passAt(200, 100, 100).getAsDouble();
        double pow = PassAtK.passPow(200, 100, 100).getAsDouble();
        assertTrue(at > 0.999999 && at <= 1.0, "" + at);
        assertTrue(pow >= 0.0 && pow < 1e-30, "" + pow);
        assertEquals(1.0, PassAtK.passPow(200, 200, 200).getAsDouble(), EPS);
        assertEquals(0.5, PassAtK.passAt(200, 100, 1).getAsDouble(), EPS);
        double mid = PassAtK.passAt(200, 190, 10).getAsDouble();
        assertTrue(mid > 0.9999999 && Double.isFinite(mid));
    }

    @Test
    void passAtKOfCEqualsOneMinusPassPowKOfTheFailures() {
        for (int n = 1; n <= 15; n++) {
            for (int c = 0; c <= n; c++) {
                for (int k = 1; k <= n; k++) {
                    assertEquals(1.0 - PassAtK.passPow(n, n - c, k).getAsDouble(),
                            PassAtK.passAt(n, c, k).getAsDouble(), EPS, "n=" + n + " c=" + c + " k=" + k);
                }
            }
        }
    }

    @Test
    void matchesTheDefinitionByCountingSubsets() {
        for (int n = 1; n <= 12; n++) {
            for (int c = 0; c <= n; c++) {
                for (int k = 1; k <= n; k++) {
                    double total = binomial(n, k).doubleValue();
                    double noSuccess = binomial(n - c, k).doubleValue() / total;
                    double allSuccess = binomial(c, k).doubleValue() / total;
                    assertEquals(1.0 - noSuccess, PassAtK.passAt(n, c, k).getAsDouble(), EPS);
                    assertEquals(allSuccess, PassAtK.passPow(n, c, k).getAsDouble(), EPS);
                }
            }
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PassAtK.passAt(5, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> PassAtK.passPow(5, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> PassAtK.passAt(5, -1, 1));
    }

    private static BigInteger binomial(int n, int k) {
        if (k > n) return BigInteger.ZERO;
        BigInteger r = BigInteger.ONE;
        for (int i = 1; i <= k; i++) r = r.multiply(BigInteger.valueOf(n - k + i)).divide(BigInteger.valueOf(i));
        return r;
    }
}

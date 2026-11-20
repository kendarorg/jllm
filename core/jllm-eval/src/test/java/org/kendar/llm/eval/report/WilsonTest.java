package org.kendar.llm.eval.report;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class WilsonTest {

    @Test
    void noTrialsGivesTheWholeRange() {
        assertArrayEquals(new double[]{0, 1}, Wilson.bounds(0, 0), 1e-9);
    }

    @Test
    void threeOfThreeStillAllowsAHalf() {
        assertTrue(Wilson.low(3, 3) < 0.5);
        assertEquals(1.0, Wilson.high(3, 3), 1e-9);
    }

    @Test
    void oneFlippedTrialOutOfThreeIsNoise() {
        assertFalse(Wilson.separated(3, 3, 2, 3));
        assertFalse(Wilson.separated(3, 3, 1, 3));
    }

    @Test
    void largeSamplesSeparate() {
        assertTrue(Wilson.separated(10, 10, 3, 10));
        assertFalse(Wilson.separated(8, 10, 7, 10));
    }
}

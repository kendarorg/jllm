package org.kendar.llm.harness.llm;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBudgetTest {

    @Test
    void delaysDoubleAndStopBeforeTotalExceedsTheBudget() {
        RetryBudget budget = new RetryBudget();
        assertEquals(OptionalLong.of(1_000), budget.reserve(1, 1_000, Optional.empty()));
        assertEquals(OptionalLong.of(2_000), budget.reserve(2, 1_000, Optional.empty()));
        assertEquals(OptionalLong.of(4_000), budget.reserve(3, 1_000, Optional.empty()));
        assertTrue(budget.reserve(4, 1_000, Optional.empty()).isEmpty());
    }

    @Test
    void retryAfterReplacesTheExponentialDelayAndCountsTowardsTheBudget() {
        RetryBudget budget = new RetryBudget();
        assertEquals(OptionalLong.of(6_000), budget.reserve(1, 1_000, Optional.of(6_000L)));
        assertTrue(budget.reserve(2, 1_000, Optional.of(5_000L)).isEmpty());
        assertEquals(OptionalLong.of(2_000), budget.reserve(2, 1_000, Optional.empty()));
    }

    @Test
    void aSingleDelayLongerThanTheBudgetIsRefusedAtOnce() {
        assertTrue(new RetryBudget().reserve(1, 60_000, Optional.empty()).isEmpty());
    }
}

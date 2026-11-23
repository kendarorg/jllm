package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunBudgetTest {

    @Test
    void modelTurnsAloneUseTheBudget() {
        RunBudget b = new RunBudget(3, 10);

        for (int i = 0; i < 3; i++) {
            assertFalse(b.exhausted(), "turn " + (i + 1) + " may start");
            b.startTurn();
        }

        assertTrue(b.exhausted());
    }

    @Test
    void innerCallsCountAsTurnsByTheRatio() {
        RunBudget b = new RunBudget(5, 4);
        b.startTurn(); // one model turn

        for (int i = 0; i < 4; i++) assertTrue(b.tryInnerCall());

        assertEquals(1, b.turns());
        assertEquals(4, b.innerCalls());
        assertEquals(2, b.used(), "one turn plus four calls at four per turn");
    }

    @Test
    void theTurnThatMakesTheCallsMayUseUpToTheLimitButNotBeyond() {
        RunBudget b = new RunBudget(2, 2);
        b.startTurn();
        b.startTurn(); // the last allowed turn: both turns counted

        assertTrue(b.tryInnerCall(), "the first call of the turn is still inside the limit");
        assertFalse(b.tryInnerCall(), "the second would be worth a turn that does not exist");
        assertEquals(1, b.innerCalls(), "a refused call is not counted");
    }

    @Test
    void whenInnerCallsUseTheBudgetTheLoopMustAsk() {
        RunBudget b = new RunBudget(2, 1);
        b.startTurn();
        assertTrue(b.tryInnerCall());

        assertTrue(b.exhausted(), "one turn plus one call is two: the limit");
        assertFalse(b.tryInnerCall());
    }

    @Test
    void zeroOrNegativeRatioDisablesCountingOfInnerCalls() {
        for (int ratio : new int[]{0, -1}) {
            RunBudget b = new RunBudget(1, ratio);
            b.startTurn();

            for (int i = 0; i < 1000; i++) assertTrue(b.tryInnerCall());

            assertEquals(1, b.used());
            assertEquals("1 turn", b.describe(), "inner calls are not counted, so not reported");
        }
    }

    @Test
    void extendingRaisesTheLimit() {
        RunBudget b = new RunBudget(1, 10);
        b.startTurn();
        assertTrue(b.exhausted());

        b.extend(5);

        assertFalse(b.exhausted());
        assertEquals(6, b.limit());
    }

    @Test
    void aRetriedTurnIsGivenBack() {
        RunBudget b = new RunBudget(1, 10);
        b.startTurn();

        b.refundTurn();

        assertFalse(b.exhausted());
        assertEquals(0, b.turns());
    }

    @Test
    void theDescriptionReportsBothCountsAndTheRefusalSaysToReturn() {
        RunBudget b = new RunBudget(10, 2);
        b.startTurn();
        b.startTurn();
        b.tryInnerCall();

        assertEquals("2 turns and 1 script tool call", b.describe());
        assertTrue(b.refusal().contains("return what you have"), b.refusal());
        assertTrue(b.refusal().contains("2 turns and 1 script tool call"), b.refusal());
    }
}

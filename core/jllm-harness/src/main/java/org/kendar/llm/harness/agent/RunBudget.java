package org.kendar.llm.harness.agent;

/**
 * The turn budget of one run of the loop: model turns plus the tool calls scripts made, divided by the ratio
 * ({@code loop.innerCallsPerTurn}). Replaces a single turn counter so that a script cannot spend unlimited work inside
 * one model turn. A sub-agent has its own budget.
 */
final class RunBudget {
    private final int ratio;
    private int limit;
    private int turns;
    private long inner;

    /**
     * @param limit            turns allowed before the user is asked to continue
     * @param innerCallsPerTurn inner calls worth one turn; zero or less: inner calls are free
     */
    RunBudget(int limit, int innerCallsPerTurn) {
        this.limit = limit;
        this.ratio = innerCallsPerTurn;
    }

    /** Counts a model turn that is about to start. */
    synchronized void startTurn() {
        turns++;
    }

    /** The model call was retried (overflow, ...): the turn did not happen. */
    synchronized void refundTurn() {
        if (turns > 0) turns--;
    }

    /** True when the loop must ask to continue before another model turn. */
    synchronized boolean exhausted() {
        return used() >= limit;
    }

    /**
     * Counts one inner call unless the budget is used: the turn that makes the call is already counted, so it may use
     * up to the limit but not beyond.
     *
     * @return false when the call must not run
     */
    synchronized boolean tryInnerCall() {
        if (ratio <= 0) return true;
        if (turns + (inner + 1) / ratio > limit) return false;
        inner++;
        return true;
    }

    /** Raises the limit by {@code turns} when the user agreed to go on. */
    synchronized void extend(int more) {
        limit += more;
    }

    synchronized int turns() {
        return turns;
    }

    synchronized long innerCalls() {
        return inner;
    }

    synchronized int limit() {
        return limit;
    }

    /** Turns plus the inner calls expressed as turns (rounded down). */
    synchronized long used() {
        return turns + (ratio > 0 ? inner / ratio : 0);
    }

    /** Both counts, for the "continue" question and the stop warning; the script calls are named only when there were some. */
    synchronized String describe() {
        String t = turns + (turns == 1 ? " turn" : " turns");
        return ratio > 0 && inner > 0 ? t + " and " + inner + (inner == 1 ? " script tool call" : " script tool calls") : t;
    }

    /** The message a script gets when its next tool call is refused. */
    String refusal() {
        return "The turn budget is used (" + describe() + "): no more tool calls; return what you have now";
    }
}

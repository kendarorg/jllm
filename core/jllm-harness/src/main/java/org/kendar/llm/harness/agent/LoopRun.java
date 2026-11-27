package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.ToolContext;

/** What one run of the loop keeps from turn to turn; shared by the loop, the model turn and the tool round. */
final class LoopRun {
    final LoopRequest request;
    final String contextKey;
    final LoopRunScope scope;
    final ToolContext toolContext;
    /** Mode note and system text after the cached prefix; fixed for the run. */
    final String turnExtra;
    final CancelToken token;
    final StuckDetector stuck;
    /** {@code main} or the label of the sub-agent: names the agent for the run guards. */
    final String agentName;
    final int[] stopAttempts = {0};
    final RunBudget budget;

    /** Usage of the last response; null after an overflow retry. */
    Usage last;
    String finalText = "";
    /** Text of the cut-off responses the current one continues. */
    String carried = "";
    int continuations;
    boolean overflowRetried;
    boolean truncationRetried;
    /** A thinking block of this turn is in the log. */
    boolean turnHadThinking;
    /** History was rewritten mid-turn: their signatures are void, stop thinking. */
    boolean thinkingOff;
    /** The agent is stuck and gets its last turn with tool use off; the run then ends as STUCK. */
    boolean wrapUp;

    LoopRun(LoopRequest request, String contextKey, LoopRunScope scope, ToolContext toolContext, String turnExtra,
            CancelToken token, StuckDetector stuck, String agentName, RunBudget budget) {
        this.request = request;
        this.contextKey = contextKey;
        this.scope = scope;
        this.toolContext = toolContext;
        this.turnExtra = turnExtra;
        this.token = token;
        this.stuck = stuck;
        this.agentName = agentName;
        this.budget = budget;
    }
}

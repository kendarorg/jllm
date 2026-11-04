package org.kendar.llm.harness.context;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.List;

/**
 * Turns the stored message log of an agent into the messages of its next request. One instance serves every chat and
 * agent of a harness; per-agent state is keyed by {@code (chatId, agentKey)}.
 */
public interface ContextStrategy {

    ContextStrategyKind kind();

    /** Every loop turn, before the model call. */
    ContextView prepare(ContextTurn turn);

    /** The provider rejected the request as too long. @return the view to retry with, or null to give up */
    ContextView onOverflow(ContextTurn turn);

    /** Manual {@code /compact}. */
    CompactResult compactNow(ContextTurn turn, String focus);

    /** Estimated size in tokens of what the next request would carry. */
    long contextTokens(String chatId, String agentKey, ArrayNode log);

    /** What a sub-agent inheriting the context gets as a copy of {@code log}: by default the log itself. */
    default ArrayNode inheritedView(String chatId, String agentKey, ArrayNode log) {
        return log;
    }

    /** Extra tools offered to every agent while this strategy is active. Same list on every call. */
    default List<HarnessTool> tools() {
        return List.of();
    }

    /** Registers the hooks the strategy needs; called once while the harness is built. */
    default void install(Hooks hooks) {
    }

    /** The agent finished and its state will not be used again (sub-agents). */
    default void release(String chatId, String agentKey) {
    }

    /** The chat was deleted. */
    default void forget(String chatId) {
    }
}

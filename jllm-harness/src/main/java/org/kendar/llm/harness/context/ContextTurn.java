package org.kendar.llm.harness.context;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.agent.EventSink;
import org.kendar.llm.harness.llm.Usage;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What a {@link ContextStrategy} gets on every turn of an agent.
 *
 * @param agentKey  identifies the agent inside the chat: the session agent id for the main agent, a per-run key for
 *                  a sub-agent
 * @param log       the agent's message log; a strategy may rewrite it only if it reports {@link ContextView#logChanged()}
 * @param inherited [0] = leading messages that are a copy of the parent's context (0 for the main agent)
 * @param lastUsage usage of the previous request of this agent; null when unknown or stale
 * @param prefix    the request the agent sends every turn, for summary requests that hit the prompt cache
 * @param usageSink receives the tokens spent by the strategy's own model calls
 * @param main      the main agent of a persisted chat (may ask the user); false for sub-agents
 */
public record ContextTurn(String chatId, String agentKey, ArrayNode log, int[] inherited, Usage lastUsage,
                          RequestPrefix prefix, EventSink sink, BooleanSupplier cancelled, Consumer<Usage> usageSink,
                          boolean main) {

    public ContextTurn {
        if (inherited == null) inherited = new int[]{0};
        if (sink == null) sink = EventSink.NOOP;
        if (cancelled == null) cancelled = () -> false;
        if (usageSink == null) usageSink = u -> { };
    }
}

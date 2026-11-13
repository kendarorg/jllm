package org.kendar.llm.harness.agent;

import java.util.function.BooleanSupplier;

/**
 * The event stream and cancel flag of the run a tool call belongs to; sub-agents forward approvals through it.
 *
 * @param contextKey the agent's key in the context strategy (see {@code ContextTurn.agentKey}); null = main agent
 */
public record AgentRun(EventSink sink, BooleanSupplier cancelled, String contextKey) {

    public AgentRun(EventSink sink, BooleanSupplier cancelled) {
        this(sink, cancelled, null);
    }
}

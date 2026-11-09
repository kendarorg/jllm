package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ThinkingLevel;

import java.util.List;

/**
 * A sub-agent type. A sub-agent runs in the mode of its caller; a {@code readOnly} type always runs as PLAN with the
 * read-only tools only.
 *
 * @param tools          tool names; empty = every tool the type may use
 * @param prompt         instructions added to the system prompt of the sub-agent
 * @param inheritContext start from a copy of the parent's messages (up to the task call) instead of an empty context.
 *                       On compaction the agent's own messages are summarized first and the copy only if that is not
 *                       enough; the parent's own context is never changed
 * @param model          model of this agent type; null = the main model
 * @param readOnly       only read-only tools, whatever the mode of the caller
 * @param temperature    sampling temperature of this agent type; null = the provider's
 * @param thinking       reasoning effort of this agent type; null = the provider's
 */
public record AgentDefinition(String name, String description, List<String> tools, String prompt,
                              boolean inheritContext, ModelClient model, boolean readOnly,
                              Double temperature, ThinkingLevel thinking) {

    public AgentDefinition {
        tools = tools == null ? List.of() : List.copyOf(tools);
        prompt = prompt == null ? "" : prompt;
    }

    public AgentDefinition(String name, String description, List<String> tools, String prompt,
                           boolean inheritContext, ModelClient model, boolean readOnly, Double temperature) {
        this(name, description, tools, prompt, inheritContext, model, readOnly, temperature, null);
    }

    public AgentDefinition(String name, String description, List<String> tools, String prompt,
                           boolean inheritContext, ModelClient model, boolean readOnly) {
        this(name, description, tools, prompt, inheritContext, model, readOnly, null);
    }

    public AgentDefinition(String name, String description, List<String> tools, String prompt,
                           boolean inheritContext, ModelClient model) {
        this(name, description, tools, prompt, inheritContext, model, false);
    }

    public AgentDefinition(String name, String description, List<String> tools, String prompt) {
        this(name, description, tools, prompt, false, null, false);
    }
}

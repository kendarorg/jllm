package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.llm.LlmSettings;

import java.util.List;

/**
 * A sub-agent type for the {@code task} tool. A sub-agent runs in the mode of its caller; {@code tools} narrows the
 * tools it gets (empty = all of them).
 *
 * @param prompt     instructions added to the system prompt of the sub-agent
 * @param promptFile file with the instructions, read at startup; used when {@code prompt} is empty
 * @param inheritContext start from a copy of the parent's messages (default false)
 * @param model      optional inline model for this agent type; default: the main model
 * @param provider   optional name of a provider from the settings list; wins over an inline {@code model}
 * @param temperature optional sampling temperature (0 to 2); default: the provider's
 * @param readOnly   only read-only tools, whatever the mode of the caller (default false)
 */
public record AgentSettings(String name, String description, List<String> tools, String prompt, String promptFile,
                            Boolean inheritContext, LlmSettings model, Boolean readOnly, String provider,
                            Double temperature) {

    public AgentSettings(String name, String description, List<String> tools, String prompt, String promptFile,
                         Boolean inheritContext, LlmSettings model, Boolean readOnly) {
        this(name, description, tools, prompt, promptFile, inheritContext, model, readOnly, null, null);
    }

    public AgentSettings(String name, String description, List<String> tools, String prompt, String promptFile) {
        this(name, description, tools, prompt, promptFile, null, null, null, null, null);
    }

    public AgentSettings {
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Agent '" + name + "': temperature must be between 0 and 2");
        }
    }
}

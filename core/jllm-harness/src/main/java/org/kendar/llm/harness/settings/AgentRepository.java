package org.kendar.llm.harness.settings;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The sub-agent types kept by a {@link ConfigProvider}. Inline prompts only: an agent that names a prompt file is
 * refused. Written by administrators only.
 */
public interface AgentRepository {

    Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");
    int MAX_PROMPT_BYTES = 64 * 1024;

    /** Nothing stored, nothing writable. */
    AgentRepository NONE = new AgentRepository() {
        @Override
        public List<AgentSettings> all() {
            return List.of();
        }

        @Override
        public Optional<AgentSettings> find(String name) {
            return Optional.empty();
        }

        @Override
        public AgentSettings write(AgentSettings agent) {
            throw new UnsupportedOperationException("Read-only");
        }

        @Override
        public boolean delete(String name) {
            return false;
        }
    };

    List<AgentSettings> all();

    Optional<AgentSettings> find(String name);

    /**
     * Inserts or replaces.
     *
     * @throws IllegalArgumentException see {@link #check}
     */
    AgentSettings write(AgentSettings agent);

    boolean delete(String name);

    /** @throws IllegalArgumentException on an invalid name, a prompt file, or a prompt over the size cap */
    static void check(AgentSettings agent) {
        if (agent == null || agent.name() == null || !NAME.matcher(agent.name()).matches()) {
            throw new IllegalArgumentException("Invalid agent name: " + (agent == null ? null : agent.name()));
        }
        if (agent.promptFile() != null) {
            throw new IllegalArgumentException("Agent '" + agent.name()
                    + "': promptFile is not allowed, give the prompt inline");
        }
        if (agent.prompt() != null && agent.prompt().getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            throw new IllegalArgumentException("Agent '" + agent.name() + "': prompt larger than "
                    + (MAX_PROMPT_BYTES / 1024) + " KB");
        }
    }
}

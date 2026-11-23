package org.kendar.llm.harness.settings;

import java.util.List;

/**
 * A shell command run through the sandbox, in the work directory, when an agent is about to finish. Exit code 0
 * lets it stop; another code or a timeout sends it back to work with the tail of the output.
 *
 * @param timeoutSeconds default 600
 * @param agents         {@code main} (default), a sub-agent type or label, or {@code *} for all
 * @param onlyIfChanged  run only if a tool that changes things ran in this run (default true)
 * @param maxOutputChars tail of the output given to the agent (default 8000)
 */
public record StopHook(String command, Integer timeoutSeconds, List<String> agents, Boolean onlyIfChanged,
                       Integer maxOutputChars) {
    public StopHook {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("hooks.stop: every entry needs a command");
        }
        agents = agents == null || agents.isEmpty() ? List.of("main") : List.copyOf(agents);
    }

    public int timeout() {
        return timeoutSeconds == null || timeoutSeconds <= 0 ? 600 : timeoutSeconds;
    }

    public boolean changedOnly() {
        return onlyIfChanged == null || onlyIfChanged;
    }

    public int maxChars() {
        return maxOutputChars == null || maxOutputChars <= 0 ? 8000 : maxOutputChars;
    }
}

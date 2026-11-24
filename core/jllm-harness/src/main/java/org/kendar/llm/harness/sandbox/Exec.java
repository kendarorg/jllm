package org.kendar.llm.harness.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Command execution of a {@link SandboxExecutor}: what the {@code bash} tool calls. */
public interface Exec {

    ExecutionResult execute(String chatId, String command, Duration timeout);

    /**
     * Runs a command from {@code runDir} instead of the work dir (plan mode: the scratch dir).
     *
     * @param readOnlyDir directory the command may see but not change (the container runner mounts it read-only); null
     *                    for none. The local runner cannot enforce it: plan mode is advisory there.
     */
    default ExecutionResult execute(String chatId, Path runDir, Path readOnlyDir, String command, Duration timeout) {
        return execute(chatId, command, timeout);
    }

    /**
     * Runs a command of an agent: from the work dir, or in plan mode from the agent's scratch directory with the work
     * dir read-only where the runner can enforce it. Runners that keep per-agent state elsewhere override it.
     */
    ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout);

    /**
     * Which variables of the harness process a shell command inherits ({@code *} patterns, see
     * {@link org.kendar.llm.harness.util.Wildcard}); the default is all. The container runner ignores it: the
     * container has an environment of its own.
     */
    default void environmentWhitelist(List<String> patterns) {
    }

    /**
     * Cap in bytes of the log a command's full output spills to once it exceeds what the result keeps ({@code <= 0}:
     * unlimited); past the cap the log ends with a truncation marker. Default 50 KB.
     */
    default void outputLogLimit(long maxBytes) {
    }

    /** The shell of commands run on this machine ({@code sandbox.shell}); the container runner ignores it. */
    default void shell(LocalShell shell) {
    }

    /** A path of this runner as the model and the user should see it (container paths use {@code /}). */
    default String displayPath(Path p) {
        return String.valueOf(p);
    }

    /**
     * Where and how the {@code bash} tool runs commands. Part of that tool's description, so a chat without the tool
     * is never told about a shell. Empty when there is nothing to add.
     */
    default String shellDescription() {
        return "";
    }
}

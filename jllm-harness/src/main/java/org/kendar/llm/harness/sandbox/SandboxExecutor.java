package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Isolation seam for command execution. Only the local-directory backend exists today;
 * a Docker backend implements the same interface later and is selected via
 * {@code jllm.harness.sandbox.backend}.
 */
public interface SandboxExecutor {

    ExecutionResult execute(String chatId, String command, Duration timeout);

    /**
     * Runs a command from {@code runDir} instead of the work dir (plan mode: the scratch dir).
     *
     * @param readOnlyDir directory the command may see but not change (docker mounts it read-only); null for none.
     *                    The local executors cannot enforce it.
     */
    default ExecutionResult execute(String chatId, Path runDir, Path readOnlyDir, String command, Duration timeout) {
        return execute(chatId, command, timeout);
    }

    /**
     * Which variables of the harness process a shell command inherits ({@code *} patterns, see
     * {@link org.kendar.llm.harness.util.Wildcard}); the default is all. Backends that start commands in a container
     * ignore it: the container has an environment of its own.
     */
    default void environmentWhitelist(java.util.List<String> patterns) {
    }

    /**
     * True when the work dir is a project shared by every chat (the CLI): a script's file jail is then the agent's
     * own scratch directory. False when the work dir belongs to the chat.
     */
    default boolean sharedWorkDir() {
        return false;
    }

    /**
     * The directory whose total size {@code storage.maxBytes} limits: everything the chat owns. The work dir by
     * default; with a shared project directory, the chat's scratch directories only.
     */
    default Path chatStorage(String chatId) {
        return workDir(chatId);
    }

    /**
     * Scratch directory of one agent of a chat: {@code <root>/<chatId>/<agentId>}, created on demand and always
     * writable, whatever the mode. Main agent and sub-agents each get their own.
     */
    default Path scratchDir(String chatId, String agentId) {
        Path dir = LocalDirSandboxExecutor.resolveJailed(workDir(chatId).toString(), agentId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create scratch dir " + dir, e);
        }
        return dir;
    }

    /**
     * Runs a command of an agent: from the work dir, or in plan mode from the agent's scratch directory with the work
     * dir read-only where the executor can enforce it. Executors that keep per-agent state elsewhere override it.
     */
    default ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout) {
        return planMode ? execute(chatId, scratchDir, workDir(chatId), command, timeout) : execute(chatId, command, timeout);
    }

    /**
     * File access for the file tools of one agent when the work dir is not on the host (the container of {@link
     * RemoteExecSandboxExecutor}); null when the tools should use the host file system ({@link #workDir}).
     *
     * @param agentId scratch id of the agent; null for a view without a scratch directory of its own
     */
    default FileBackend files(String chatId, String agentId) {
        return null;
    }

    /**
     * After the user agreed to run without isolation because the container sandbox is unavailable: from now on this chat
     * uses the host directories and plain processes. Returns false when the executor has no such fallback or the
     * configuration forbids it ({@code localDirSandboxAllowed}).
     */
    default boolean allowLocalFallback(String chatId) {
        return false;
    }

    /**
     * Copies the chat's container files back to the host (remote docker, where nothing is mounted). Returns what was
     * done, or null when the executor needs no backup.
     */
    default String backup(String chatId) {
        return null;
    }

    /** True once {@link #allowLocalFallback} was granted for the chat. */
    default boolean localFallbackActive(String chatId) {
        return false;
    }

    /** A path of this executor as the model and the user should see it (container paths use {@code /}). */
    default String displayPath(Path p) {
        return String.valueOf(p);
    }

    /**
     * Removes the sandbox resources of one chat that live outside the work dir (the container). Returns what was
     * removed, or null when the executor has none. Project files are never removed.
     */
    default String clean(String chatId) {
        return null;
    }

    /** Releases resources held by the executor (web containers); a kept CLI container stays. */
    default void close() {
    }

    /** Root directory holding the per-chat working directories (absolute, normalized). */
    Path location();

    /** Where and how the {@code bash} tool runs commands, told to the model so it uses the right shell syntax. */
    default String shellEnvironment() {
        return "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + ".";
    }

    /** Per-chat working directory, created on demand. Also the root of the file tools' path jail. */
    Path workDir(String chatId);

    /** Deletes the per-chat working directory and everything in it (symlinks are removed, never followed). */
    default void deleteWorkDir(String chatId) {
        Path dir = workDir(chatId);
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete work dir " + dir, e);
        }
    }
}

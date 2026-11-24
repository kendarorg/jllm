package org.kendar.llm.harness.sandbox;

import java.nio.file.Path;

/**
 * Where a chat works on the host: work directory, per-agent scratch directories and what the chat owns. Independent of
 * how commands are isolated (the runner: {@link LocalSandboxExecutor} or {@link RemoteExecSandboxExecutor}).
 * Implementations: {@link DirLayout} (library default), {@link CwdLayout} (CLI), {@link ChatDirLayout} (web).
 */
public interface Layout {

    /** Root directory of the layout (absolute, normalized): the base of the per-chat directories, or the project. */
    Path location();

    /** Per-chat working directory, created on demand. Also the root of the file tools' path jail. */
    Path workDir(String chatId);

    /**
     * Scratch directory of one agent of a chat, created on demand and always writable, whatever the mode. Main agent
     * and sub-agents each get their own.
     */
    Path scratchDir(String chatId, String agentId);

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
     * Deletes what the chat owns on the host (symlinks and junctions are removed, never followed). Project files are
     * never removed.
     */
    void deleteWorkDir(String chatId);

    /** Validates {@code id} and resolves {@code base/id}, guaranteeing it stays under base. */
    static Path resolveJailed(String baseDir, String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}") || id.contains("..")) {
            throw new IllegalArgumentException("Invalid chatId");
        }
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path dir = base.resolve(id).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("Invalid chatId");
        }
        return dir;
    }
}

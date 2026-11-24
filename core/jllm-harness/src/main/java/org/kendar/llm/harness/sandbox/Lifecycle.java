package org.kendar.llm.harness.sandbox;

/** Fallback, backup, clean-up and shutdown of a {@link SandboxExecutor}. */
public interface Lifecycle {

    /**
     * After the user agreed to run without isolation because the container sandbox is unavailable: from now on this chat
     * uses the host directories and plain processes. Returns false when the runner has no such fallback or the
     * configuration forbids it ({@code localDirSandboxAllowed}).
     */
    default boolean allowLocalFallback(String chatId) {
        return false;
    }

    /** True once {@link #allowLocalFallback} was granted for the chat. */
    default boolean localFallbackActive(String chatId) {
        return false;
    }

    /**
     * Copies the chat's container files back to the host (nothing is mounted there). Returns what was done, or null
     * when the runner needs no backup.
     */
    default String backup(String chatId) {
        return null;
    }

    /**
     * Removes the sandbox resources of one chat that live outside the work dir (the container). Returns what was
     * removed, or null when the runner has none. Project files are never removed.
     */
    default String clean(String chatId) {
        return null;
    }

    /** Releases resources held by the runner (web containers); a kept CLI container stays. */
    default void close() {
    }

    /**
     * One line describing the sandbox of the chat for {@code /status}: runner, profile, scope, container, work dir and
     * where {@code run_js} keeps its files.
     */
    default String sandboxStatus(String chatId) {
        return "runner: unknown";
    }
}

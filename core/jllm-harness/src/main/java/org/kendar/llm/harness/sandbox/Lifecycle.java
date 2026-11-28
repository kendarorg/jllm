package org.kendar.llm.harness.sandbox;

/** Fallback, backup, clean-up and shutdown of a {@link SandboxExecutor}. */
public interface Lifecycle {

    /**
     * After the user agreed to run without isolation because the container sandbox is unavailable: from now on this chat
     * runs its commands in a {@link SharedLocalSandbox} (minimal environment, confined where the OS allows it) in the host
     * directories. Returns false when the runner has no such fallback, it is not {@link #localFallbackAllowed} or the
     * shared-local sandbox cannot start (Linux without a working bwrap).
     */
    default boolean allowLocalFallback(String chatId) {
        return false;
    }

    /**
     * Whether the configuration (the CLI) or the role of the chat (the web, set by an administrator) lets the chat
     * continue in a {@link SharedLocalSandbox} when the container sandbox is unavailable. Never the plain host.
     */
    default boolean localFallbackAllowed(String chatId) {
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
     * Whether commands of the chat can reach the network (and so fetch outside content, or send data out). A shell
     * result is then treated like other outside content: it taints the chat. A plain host shell is not tracked here
     * (false): it is not a sandbox, and each command asks in the default modes.
     */
    default boolean networkAccess(String chatId) {
        return false;
    }

    /**
     * One line describing the sandbox of the chat for {@code /status}: runner, profile, scope, container, work dir and
     * where {@code run_js} keeps its files.
     */
    default String sandboxStatus(String chatId) {
        return "runner: unknown";
    }
}

package org.kendar.llm.harness.sandbox;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Isolation seam of the harness, made of four narrow parts: {@link Layout} (where a chat works on the host),
 * {@link Exec} (running commands), {@link SandboxFiles} (file access when the work dir is not on the host) and
 * {@link Lifecycle} (fallback, backup, clean, close).
 * <p>
 * Two runners exist: {@link LocalSandboxExecutor} (plain processes on this machine, no isolation, for testing and
 * trusted single-user use) and {@link RemoteExecSandboxExecutor} (a long-running container with an exec daemon). Build
 * one with {@link SandboxFactory}. {@code run_js} (jllm-js) is not a runner: it sits next to whichever runner is chosen.
 */
public interface SandboxExecutor extends Layout, Exec, SandboxFiles, Lifecycle {

    @Override
    default ExecutionResult execute(String chatId, Path scratchDir, boolean planMode, String command, Duration timeout) {
        return planMode ? execute(chatId, scratchDir, workDir(chatId), command, timeout) : execute(chatId, command, timeout);
    }
}

package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.util.Uuids;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs each command in a throwaway container (docker CLI): no network, resource limits,
 * read-only rootfs, non-root, only the per-chat directory mounted at /workspace.
 */
public class DockerSandboxExecutor implements SandboxExecutor {

    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);

    private final HarnessConfig config;
    private final DockerCommandRunner runner;
    private final String dockerHost;
    /** Directory layout (work, scratch, delete) and the fallback when docker is unavailable. */
    private final LocalDirSandboxExecutor localFallback;
    private volatile boolean verified;

    public DockerSandboxExecutor(HarnessConfig config, DockerCommandRunner runner) {
        this(config, runner, System.getenv("DOCKER_HOST"));
    }

    /**
     * Docker for the commands, {@code layout} for the directories: pass a {@link CwdSandboxExecutor} or a
     * {@link ChatDirSandboxExecutor} to mount the project directory or the chat directory into the container.
     */
    public DockerSandboxExecutor(HarnessConfig config, DockerCommandRunner runner, LocalDirSandboxExecutor layout) {
        this(config, runner, System.getenv("DOCKER_HOST"), layout);
    }

    DockerSandboxExecutor(HarnessConfig config, DockerCommandRunner runner, String dockerHost) {
        this(config, runner, dockerHost, new LocalDirSandboxExecutor(config));
    }

    DockerSandboxExecutor(HarnessConfig config, DockerCommandRunner runner, String dockerHost,
                          LocalDirSandboxExecutor layout) {
        this.config = config;
        this.runner = runner;
        this.dockerHost = dockerHost;
        this.localFallback = layout;
    }

    /** A remote daemon cannot see the host work dir, so a bind mount would silently use the wrong files. */
    boolean remoteDaemon() {
        return isRemote(dockerHost);
    }

    /** True when {@code dockerHost} (the DOCKER_HOST value) points at a daemon that is not on this machine. */
    static boolean isRemote(String dockerHost) {
        if (dockerHost == null || dockerHost.isBlank() || dockerHost.startsWith("unix:") || dockerHost.startsWith("npipe:")) {
            return false;
        }
        String h = dockerHost.toLowerCase();
        return !(h.contains("://localhost") || h.contains("://127.0.0.1") || h.contains("://[::1]"));
    }

    @Override
    public void environmentWhitelist(java.util.List<String> patterns) {
        localFallback.environmentWhitelist(patterns);
    }

    @Override
    public boolean sharedWorkDir() {
        return localFallback.sharedWorkDir();
    }

    @Override
    public Path chatStorage(String chatId) {
        return localFallback.chatStorage(chatId);
    }

    @Override
    public Path location() {
        return localFallback.location();
    }

    @Override
    public Path workDir(String chatId) {
        return localFallback.workDir(chatId);
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        return localFallback.scratchDir(chatId, agentId);
    }

    @Override
    public void deleteWorkDir(String chatId) {
        localFallback.deleteWorkDir(chatId);
    }

    @Override
    public String shellEnvironment() {
        return "The bash tool runs sh -c (POSIX sh, not bash) in a Linux container (" + config.sandbox().docker().image()
                + ") with the working directory mounted at /workspace"
                + ("none".equals(config.sandbox().docker().network()) ? " and no network access." : ".");
    }

    /** Exact argv of the {@code docker run} for a command. */
    List<String> runArgs(String containerName, Path dir, String command) {
        return runArgs(containerName, dir, null, command);
    }

    /** As above; {@code readOnlyDir} (plan mode: the project directory) is mounted read-only at /project. */
    List<String> runArgs(String containerName, Path dir, Path readOnlyDir, String command) {
        List<String> a = new ArrayList<>();
        a.add(config.sandbox().docker().binary());
        a.addAll(List.of("run", "--rm", "--name", containerName,
                "--network", config.sandbox().docker().network(),
                "--memory", config.sandbox().docker().memory(),
                "--cpus", config.sandbox().docker().cpus(),
                "--pids-limit", String.valueOf(config.sandbox().docker().pidsLimit()),
                "--read-only", "--tmpfs", "/tmp:rw,size=64m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", config.sandbox().docker().user(),
                "-e", "HOME=/tmp",
                "-v", mountSource(dir) + ":/workspace", "-w", "/workspace"));
        if (readOnlyDir != null) a.addAll(List.of("-v", mountSource(readOnlyDir) + ":/project:ro"));
        a.addAll(List.of(config.sandbox().docker().image(), "sh", "-c", command));
        return a;
    }

    /** Absolute, forward-slash host path: a relative or backslash path makes docker misparse "-v src:dst" on Windows. */
    static String mountSource(Path dir) {
        return dir.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    @Override
    public ExecutionResult execute(String chatId, String command, Duration timeout) {
        return execute(chatId, workDir(chatId), null, command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path dir, Path readOnlyDir, String command, Duration timeout) {
        ExecutionResult unavailable = remoteDaemon()
                ? new ExecutionResult("", "Docker sandbox unavailable: DOCKER_HOST=" + dockerHost
                + " is a remote daemon and cannot mount the local work dir.", 127, false)
                : verifyDocker();
        if (unavailable != null) {
            if (config.sandbox().localDirSandboxAllowed()) {
                return localFallback.execute(chatId, dir, readOnlyDir, command, timeout);
            }
            return new ExecutionResult(unavailable.stdout(), unavailable.stderr()
                    + " Allow the local directory sandbox (HarnessConfig.Sandbox.localDirSandboxAllowed) to fall back to a local directory.",
                    unavailable.exitCode(), unavailable.timedOut());
        }
        String name = "harness-" + chatId + "-" + Uuids.v7String();
        try {
            DockerCommandRunner.Result r = runner.run(runArgs(name, dir, readOnlyDir, command), timeout);
            if (r.timedOut()) {
                kill(name);
                return new ExecutionResult(r.stdout(), r.stderr(), -1, true);
            }
            return new ExecutionResult(r.stdout(), r.stderr(), r.exitCode(), false);
        } catch (InterruptedException e) {
            kill(name);
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Interrupted", -1, false);
        } catch (Exception e) {
            kill(name);
            return new ExecutionResult("", "Execution failed: " + e.getMessage(), -1, false);
        }
    }

    private void kill(String name) {
        boolean interrupted = Thread.interrupted();
        try {
            runner.run(List.of(config.sandbox().docker().binary(), "kill", name), CONTROL_TIMEOUT);
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (Exception ignored) {
            // container may already be gone
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Checked lazily at first use (not at startup); success is cached, failure is retried. */
    private ExecutionResult verifyDocker() {
        if (verified) return null;
        String bin = config.sandbox().docker().binary();
        String msg;
        try {
            DockerCommandRunner.Result r = runner.run(
                    List.of(bin, "version", "--format", "{{.Server.Version}}"), CONTROL_TIMEOUT);
            if (r.exitCode() == 0 && !r.timedOut()) {
                verified = true;
                return null;
            }
            msg = "Docker sandbox unavailable: '" + bin + " version' failed (is the docker daemon running?): "
                    + r.stderr().strip();
        } catch (IOException e) {
            msg = "Docker sandbox unavailable: docker binary '" + bin + "' not found or not executable ("
                    + e.getMessage() + "). Install Docker or set jllm.harness.sandbox.backend=local.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Interrupted", -1, false);
        }
        return new ExecutionResult("", msg, 127, false);
    }
}

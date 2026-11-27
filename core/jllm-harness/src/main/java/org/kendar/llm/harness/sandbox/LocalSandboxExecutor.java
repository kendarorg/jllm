package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The local runner: plain subprocesses on this machine in the directories of a {@link Layout}. No isolation, network
 * or resource limits: commands run as this process's user. For testing and trusted single-user use only; use
 * {@link RemoteExecSandboxExecutor} for anything real. Plan mode is advisory here: file tools are blocked from writing
 * but a shell command can still write.
 */
public class LocalSandboxExecutor implements SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalSandboxExecutor.class);

    private final Layout layout;
    private volatile LocalShell shell = LocalShell.osDefault();
    private final AtomicBoolean warned = new AtomicBoolean();
    private final boolean confinesPaths;

    public LocalSandboxExecutor(Layout layout) {
        this(layout, true);
    }

    private LocalSandboxExecutor(Layout layout, boolean confinesPaths) {
        this.layout = layout;
        this.confinesPaths = confinesPaths;
    }

    /**
     * The local runner without the path jail: the file tools take any host path and shell command lines are not
     * checked. Only for a disposable environment (a benchmark container) that is itself the sandbox.
     */
    public static LocalSandboxExecutor unconfined(Layout layout) {
        return new LocalSandboxExecutor(layout, false);
    }

    @Override
    public boolean confinesPaths() {
        return confinesPaths;
    }

    /** The runner over the default {@link DirLayout} of the configuration ({@code sandbox.baseDir}). */
    public static LocalSandboxExecutor of(HarnessConfig config) {
        return new LocalSandboxExecutor(DirLayout.of(config));
    }

    /** The layout this runner works in. */
    public Layout layout() {
        return layout;
    }

    // ── layout ────────────────────────────────────────────────────────────────

    @Override
    public Path location() {
        return layout.location();
    }

    @Override
    public Path workDir(String chatId) {
        return layout.workDir(chatId);
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        return layout.scratchDir(chatId, agentId);
    }

    @Override
    public boolean sharedWorkDir() {
        return layout.sharedWorkDir();
    }

    @Override
    public Path chatStorage(String chatId) {
        return layout.chatStorage(chatId);
    }

    @Override
    public void deleteWorkDir(String chatId) {
        layout.deleteWorkDir(chatId);
    }

    // ── commands ──────────────────────────────────────────────────────────────

    @Override
    public String sandboxStatus(String chatId) {
        return (confinesPaths
                ? "runner: local (no isolation: commands run on this machine as this user; testing and trusted use only)"
                : "runner: none (no isolation and no path jail: every tool reaches any path of this machine)")
                + " | work dir: " + workDir(chatId) + " | run_js storage: host";
    }

    @Override
    public String shellDescription() {
        return shell.description();
    }

    @Override
    public void shell(LocalShell shell) {
        if (shell != null) this.shell = shell;
    }

    @Override
    public ExecutionResult execute(String chatId, String command, Duration timeout) {
        return run(workDir(chatId), command, timeout);
    }

    @Override
    public ExecutionResult execute(String chatId, Path runDir, Path readOnlyDir, String command, Duration timeout) {
        return run(runDir, command, timeout);
    }

    /** Bytes of command output kept in memory; the complete output goes to a log file beyond this. */
    static final int CAPTURE_LIMIT = 50 * 1024;

    /**
     * How long the output is still read after the command exited. A background child that inherited the output pipe
     * keeps it open: past this the result is returned with the output read so far (the child keeps running).
     */
    static final Duration READER_GRACE = Duration.ofSeconds(2);

    private volatile List<String> envWhitelist = org.kendar.llm.harness.util.EnvWhitelist.ALL;
    private volatile long logLimit = TailCapture.DEFAULT_LOG_LIMIT;

    @Override
    public void environmentWhitelist(List<String> patterns) {
        this.envWhitelist = org.kendar.llm.harness.util.EnvWhitelist.orAll(patterns);
    }

    @Override
    public void outputLogLimit(long maxBytes) {
        this.logLimit = maxBytes;
    }

    /** Current cap of the spill log ({@link #outputLogLimit}). */
    long outputLogLimit() {
        return logLimit;
    }

    private ExecutionResult run(Path dir, String command, Duration timeout) {
        if (warned.compareAndSet(false, true)) {
            log.warn("The local sandbox runner has NO isolation: agent commands run as this process's user "
                    + "(work dirs under {}). Use RemoteExecSandboxExecutor (sandbox.backend=docker) for untrusted input.",
                    layout.location());
        }
        List<String> cmd = shell.argv(command);
        Process p = null;
        TailCapture capture = new TailCapture(CAPTURE_LIMIT, logLimit);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            org.kendar.llm.harness.util.EnvWhitelist.apply(pb.environment(), envWhitelist);
            p = pb.start();
            p.getOutputStream().close();
            InputStream stdout = p.getInputStream();
            CompletableFuture<TailCapture> out = capture.start(stdout);
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            capture.await(out, stdout, finished ? READER_GRACE : Duration.ofSeconds(5));
            return new ExecutionResult(capture.text(), "", finished ? p.exitValue() : -1, !finished,
                    capture.omittedLines(), capture.log());
        } catch (InterruptedException e) {
            if (p != null) { // cancelled: do not leave the command running
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
            capture.detach();
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Interrupted", -1, false);
        } catch (Exception e) { // boundary: sandbox boundary: detach, report the failure
            capture.detach();
            return new ExecutionResult("", "Execution failed: " + e.getMessage(), -1, false);
        }
    }
}

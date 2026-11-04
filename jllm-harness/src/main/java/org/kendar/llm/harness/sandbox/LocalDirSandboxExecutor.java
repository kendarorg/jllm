package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Default executor: plain subprocess in {@code base-dir/{chatId}}. No isolation, network or resource limits. */
public class LocalDirSandboxExecutor implements SandboxExecutor {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final Logger log = LoggerFactory.getLogger(LocalDirSandboxExecutor.class);

    private final HarnessConfig config;

    public LocalDirSandboxExecutor(HarnessConfig config) {
        this.config = config;
        log.warn("LocalDirSandboxExecutor has NO isolation: agent commands run as this process's user in {}. "
                + "Use a SandboxExecutor such as DockerSandboxExecutor for untrusted input.", config.sandbox().baseDir());
    }

    @Override
    public Path location() {
        return Path.of(config.sandbox().baseDir()).toAbsolutePath().normalize();
    }

    @Override
    public String shellEnvironment() {
        String os = "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + ". ";
        return WINDOWS
                ? os + "The bash tool runs Windows cmd.exe (cmd /c), not bash: use cmd syntax (dir, type, findstr, "
                        + "where, &&, 2>nul). head, tail, grep, ls, cat, sed and /dev/null do not exist."
                : os + "The bash tool runs bash -c.";
    }

    @Override
    public Path workDir(String chatId) {
        Path dir = resolveJailed(config.sandbox().baseDir(), chatId);
        Process p = null;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create work dir " + dir, e);
        }
        return dir;
    }

    /** Validates chatId and resolves {@code base/chatId}, guaranteeing it stays under base. */
    static Path resolveJailed(String baseDir, String chatId) {
        if (chatId == null || !chatId.matches("[A-Za-z0-9._-]{1,64}") || chatId.contains("..")) {
            throw new IllegalArgumentException("Invalid chatId");
        }
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path dir = base.resolve(chatId).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("Invalid chatId");
        }
        return dir;
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

    private ExecutionResult run(Path dir, String command, Duration timeout) {
        List<String> cmd = WINDOWS ? List.of("cmd", "/c", command) : List.of("bash", "-c", command);
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            final Process proc = p;
            p.getOutputStream().close();
            TailCapture capture = new TailCapture(CAPTURE_LIMIT);
            CompletableFuture<TailCapture> out = CompletableFuture.supplyAsync(() -> capture.read(proc.getInputStream()));
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            out.get(5, TimeUnit.SECONDS);
            return new ExecutionResult(capture.text(), "", finished ? p.exitValue() : -1, !finished,
                    capture.omittedLines(), capture.log());
        } catch (InterruptedException e) {
            if (p != null) { // cancelled: do not leave the command running
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Interrupted", -1, false);
        } catch (Exception e) {
            return new ExecutionResult("", "Execution failed: " + e.getMessage(), -1, false);
        }
    }
}

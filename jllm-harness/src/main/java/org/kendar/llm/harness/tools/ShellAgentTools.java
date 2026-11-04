package org.kendar.llm.harness.tools;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.sandbox.ExecutionResult;
import org.kendar.llm.harness.sandbox.SandboxExecutor;

import java.time.Duration;
import java.util.List;

public class ShellAgentTools implements HarnessToolSet {

    private final SandboxExecutor sandbox;
    private final HarnessConfig config;

    public ShellAgentTools(SandboxExecutor sandbox, HarnessConfig config) {
        this.sandbox = sandbox;
        this.config = config;
    }

    @Override
    public List<HarnessTool> tools() {
        return List.of(new SimpleTool("bash",
                "Run a shell command in the working directory (also usable for python scripts). In plan mode it runs "
                        + "in the scratch directory and must not change the working directory.",
                SimpleTool.schema(
                        "command", "Command line to execute",
                        "timeout_seconds:integer?", "Timeout in seconds (default from config)"),
                "Shell", ToolKind.OTHER, (ctx, in) -> {
            int secs = in.path("timeout_seconds").asInt(config.sandbox().timeoutSeconds());
            Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(secs, 600)));
            String command = in.path("command").asText();
            ExecutionResult r = sandbox.execute(ctx.chatId(), ctx.scratchDir(), ctx.readOnlyWork(), command, timeout);
            StringBuilder sb = new StringBuilder();
            sb.append("exit code: ").append(r.exitCode());
            if (r.timedOut()) sb.append(" (timed out)");
            if (r.logFile() != null) {
                sb.append("\n[output truncated: ").append(r.omittedLines()).append(" lines omitted; full log: ")
                        .append(ctx.files() != null ? sandbox.displayPath(r.logFile()) : keepLog(ctx, r.logFile())).append("]");
            }
            if (!r.stdout().isBlank()) sb.append("\n[stdout]\n").append(r.stdout());
            if (!r.stderr().isBlank()) sb.append("\n[stderr]\n").append(r.stderr());
            return sb.toString();
        }, null, TruncationMode.TAIL));
    }

    private static final java.util.concurrent.atomic.AtomicInteger LOGS = new java.util.concurrent.atomic.AtomicInteger();

    /** Moves the full log into the agent scratch dir (readable by the file tools); falls back to its temp path. */
    private static String keepLog(ToolContext ctx, java.nio.file.Path log) {
        try {
            if (ctx.scratchDir() != null) {
                java.nio.file.Files.createDirectories(ctx.scratchDir());
                java.nio.file.Path target = ctx.scratchDir().toAbsolutePath()
                        .resolve("bash-" + LOGS.incrementAndGet() + ".log");
                java.nio.file.Files.move(log, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target.toString();
            }
        } catch (java.io.IOException ignored) {
        }
        return log.toString();
    }
}

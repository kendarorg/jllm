package org.kendar.llm.harness.hooks;

import org.kendar.llm.harness.safety.ToolOutputSanitizer;
import org.kendar.llm.harness.sandbox.ExecutionResult;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.time.Duration;
import java.util.function.BiPredicate;

/**
 * Verify-on-stop from the settings ({@code hooks.stop}): runs a command (e.g. {@code mvn -q test}) through the
 * sandbox, in the chat's work directory, when an agent is about to finish. Exit code 0 lets it stop; another code or a
 * timeout vetoes the stop with the tail of the output, where build and test errors are. The output is treated like
 * tool output: sanitized and bounded.
 */
public final class CommandStopHook implements Hook {

    private final HarnessSettings.StopHook spec;
    private final SandboxExecutor sandbox;
    private final BiPredicate<String, String> changedInRun;
    private final ToolOutputSanitizer sanitizer;

    /**
     * @param changedInRun (chatId, agent) whether a tool that changes things ran in the current run of the agent
     * @param sanitizer    cleaning of the output, as for tool output
     */
    public CommandStopHook(HarnessSettings.StopHook spec, SandboxExecutor sandbox,
                           BiPredicate<String, String> changedInRun, ToolOutputSanitizer sanitizer) {
        this.spec = spec;
        this.sandbox = sandbox;
        this.changedInRun = changedInRun;
        this.sanitizer = sanitizer;
    }

    @Override
    public HookResult run(HookContext ctx) {
        if (ctx.event() != HookEvent.STOP || !appliesTo(ctx.agent())) return HookResult.allow();
        if (spec.changedOnly() && !changedInRun.test(ctx.chatId(), ctx.agent())) return HookResult.allow();
        ExecutionResult r = sandbox.execute(ctx.chatId(), spec.command(), Duration.ofSeconds(spec.timeout()));
        if (r.exitCode() == 0 && !r.timedOut()) return HookResult.allow();
        String out = joined(r);
        int max = spec.maxChars();
        if (out.length() > max) out = "…[" + (out.length() - max) + " chars omitted]\n" + out.substring(out.length() - max);
        if (sanitizer != null) out = sanitizer.sanitize("stop hook", ToolOutputSanitizer.Trust.LOW, out).text();
        String head = "`" + spec.command() + "` " + (r.timedOut() ? "timed out after " + spec.timeout() + " s"
                : "exited with code " + r.exitCode());
        return HookResult.continueWith(out.isBlank() ? head + " (no output)." : head + ":\n" + out);
    }

    /** {@code main}, {@code *}, a sub-agent label ({@code "general #2"}) or its type ({@code general}). */
    boolean appliesTo(String agent) {
        String a = agent == null ? HookContext.MAIN : agent;
        int hash = a.lastIndexOf(" #");
        String type = hash > 0 ? a.substring(0, hash) : a;
        for (String want : spec.agents()) {
            if ("*".equals(want) || want.equals(a) || (hash > 0 && want.equals(type))) return true;
        }
        return false;
    }

    private static String joined(ExecutionResult r) {
        String out = r.stdout() == null ? "" : r.stdout().strip();
        String err = r.stderr() == null ? "" : r.stderr().strip();
        if (out.isEmpty()) return err;
        if (err.isEmpty()) return out;
        return out + "\n" + err;
    }
}

package org.kendar.llm.harness.hooks;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.safety.ToolOutputSanitizer;
import org.kendar.llm.harness.sandbox.ExecutionResult;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CommandStopHookTest {

    private final SandboxExecutor box = mock(SandboxExecutor.class);

    private CommandStopHook hook(HarnessSettings.StopHook spec, boolean changed) {
        return new CommandStopHook(spec, box, (chat, agent) -> changed,
                new ToolOutputSanitizer(ToolOutputSanitizer.Mode.OFF, List.of()));
    }

    private static HarnessSettings.StopHook spec(List<String> agents, Boolean onlyIfChanged, Integer max) {
        return new HarnessSettings.StopHook("mvn -q test", 30, agents, onlyIfChanged, max);
    }

    private static HookContext stop(String agent) {
        return HookContext.stop("chat", agent, "done", 1);
    }

    @Test
    void exitZeroLetsTheAgentStop() {
        when(box.execute(eq("chat"), eq("mvn -q test"), any())).thenReturn(new ExecutionResult("ok", "", 0, false));
        assertFalse(hook(spec(null, null, null), true).run(stop("main")).continues());
        verify(box).execute("chat", "mvn -q test", Duration.ofSeconds(30));
    }

    @Test
    void aFailureSendsTheTailOfTheOutput() {
        String out = "x".repeat(500) + "\nFAILED: FooTest";
        when(box.execute(anyString(), anyString(), any())).thenReturn(new ExecutionResult(out, "err line", 1, false));
        HookResult r = hook(spec(null, null, 100), true).run(stop("main"));
        assertTrue(r.continues());
        assertTrue(r.continueWith().startsWith("`mvn -q test` exited with code 1:"), r.continueWith());
        assertTrue(r.continueWith().endsWith("FAILED: FooTest\nerr line"), r.continueWith());
        assertTrue(r.continueWith().contains("chars omitted"));
        assertTrue(r.continueWith().length() < 200);
    }

    @Test
    void aTimeoutIsAFailure() {
        when(box.execute(anyString(), anyString(), any())).thenReturn(new ExecutionResult("", "", -1, true));
        HookResult r = hook(spec(null, null, null), true).run(stop("main"));
        assertTrue(r.continueWith().contains("timed out after 30 s"), r.continueWith());
    }

    @Test
    void nothingChangedNothingRuns() {
        assertFalse(hook(spec(null, null, null), false).run(stop("main")).continues());
        assertFalse(hook(spec(null, true, null), false).run(stop("main")).continues());
        verifyNoInteractions(box);
        when(box.execute(anyString(), anyString(), any())).thenReturn(new ExecutionResult("", "", 2, false));
        assertTrue(hook(spec(null, false, null), false).run(stop("main")).continues());
    }

    @Test
    void theAgentsFilterIsRespected() {
        when(box.execute(anyString(), anyString(), any())).thenReturn(new ExecutionResult("", "", 1, false));
        CommandStopHook mainOnly = hook(spec(null, null, null), true);
        assertTrue(mainOnly.appliesTo("main"));
        assertFalse(mainOnly.appliesTo("general #2"));
        assertFalse(mainOnly.run(stop("general #2")).continues());
        CommandStopHook typed = hook(spec(List.of("coder"), null, null), true);
        assertTrue(typed.appliesTo("coder #7"));
        assertFalse(typed.appliesTo("main"));
        assertTrue(hook(spec(List.of("*"), null, null), true).appliesTo("anything #1"));
        assertTrue(hook(spec(List.of("general #2"), null, null), true).appliesTo("general #2"));
    }

    @Test
    void otherEventsAreIgnored() {
        assertFalse(hook(spec(null, null, null), true).run(HookContext.prompt("chat", "hi")).continues());
        verifyNoInteractions(box);
    }
}

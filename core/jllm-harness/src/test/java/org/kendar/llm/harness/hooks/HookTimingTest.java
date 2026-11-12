package org.kendar.llm.harness.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HookTimingTest {

    @Test
    void durationAndUsageReachTheHooks() {
        Hooks hooks = new Hooks();
        HookContext[] seen = new HookContext[2];
        hooks.add(HookEvent.POST_LLM, c -> {
            seen[0] = c;
            return HookResult.allow();
        });
        hooks.add(HookEvent.POST_TOOL, c -> {
            seen[1] = c;
            return HookResult.allow();
        });
        hooks.run(HookContext.afterLlm("c", "main", "t", false, 42, null));
        hooks.run(HookContext.postTool("c", "bash", null, "o", false, 7));
        assertEquals(42, seen[0].durationMs());
        assertEquals(7, seen[1].durationMs());
    }

    @Test
    void legacyFactoriesReportNoTiming() {
        assertEquals(0, HookContext.after(HookEvent.POST_LLM, "c", "main", "t", false).durationMs());
    }
}

package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.HarnessConfig;

/**
 * Run-loop guards; unset values keep the defaults of {@link HarnessConfig.Loop}.
 *
 * @param stuck  {@code enabled} (true), {@code repeat} (3), {@code alternationCycles} (3), {@code malformed} (3)
 * @param verify {@code maxReentries} (3): times a STOP hook may send the agent back to work
 * @param innerCallsPerTurn (10) tool calls made by {@code run_js} scripts that cost one model turn of the turn budget;
 *                          zero or less turns the counting off
 */
public record LoopSettings(Stuck stuck, Verify verify, Integer innerCallsPerTurn) {
    public record Stuck(Boolean enabled, Integer repeat, Integer alternationCycles, Integer malformed) {
    }

    public record Verify(Integer maxReentries) {
    }

    HarnessConfig.Loop over(HarnessConfig.Loop d) {
        HarnessConfig.Stuck s = d.stuck();
        HarnessConfig.Stuck st = stuck == null ? s : new HarnessConfig.Stuck(
                stuck.enabled() != null ? stuck.enabled() : s.enabled(),
                stuck.repeat() != null ? stuck.repeat() : s.repeat(),
                stuck.alternationCycles() != null ? stuck.alternationCycles() : s.alternationCycles(),
                stuck.malformed() != null ? stuck.malformed() : s.malformed());
        HarnessConfig.Verify v = verify == null || verify.maxReentries() == null ? d.verify()
                : new HarnessConfig.Verify(verify.maxReentries());
        return new HarnessConfig.Loop(st, v, innerCallsPerTurn != null ? innerCallsPerTurn : d.innerCallsPerTurn());
    }
}

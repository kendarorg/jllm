package org.kendar.llm.harness.settings;

import java.util.List;

/** @param stop command hooks run when an agent is about to finish (verify-on-stop) */
public record HookSettings(List<StopHook> stop) {
    public HookSettings {
        stop = stop == null ? List.of() : List.copyOf(stop);
    }
}

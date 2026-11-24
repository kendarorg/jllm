package org.kendar.llm.web.spring;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.web.commons.HarnessFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** Triggers {@link HarnessFactory#sweepOrphanContainers} once the application is up. */
class SandboxSweep {

    private final AgentHarness harness;

    SandboxSweep(AgentHarness harness) {
        this.harness = harness;
    }

    @EventListener(ApplicationReadyEvent.class)
    void sweep() {
        HarnessFactory.sweepOrphanContainers(harness);
    }
}

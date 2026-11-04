package org.kendar.llm.web;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup, in docker daemon mode, removes the web sandbox containers whose chat no longer exists (left behind by a
 * crash or a chat deleted while the application was down). CLI containers are never touched.
 */
@Component
class SandboxSweep {

    private static final Logger log = LoggerFactory.getLogger(SandboxSweep.class);

    private final AgentHarness harness;

    SandboxSweep(AgentHarness harness) {
        this.harness = harness;
    }

    @EventListener(ApplicationReadyEvent.class)
    void sweep() {
        if (!(harness.sandbox() instanceof RemoteExecSandboxExecutor remote)) return;
        var removed = remote.sweepOrphans(chatId -> harness.sessions().find(chatId).isPresent());
        if (!removed.isEmpty()) log.info("Removed orphan sandbox containers: {}", removed);
    }
}

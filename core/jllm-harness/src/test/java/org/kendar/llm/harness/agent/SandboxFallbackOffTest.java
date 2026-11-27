package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.sandbox.LocalSandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxUnavailableException;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** Docker down and the fallback off (the default): the tool call fails, nothing runs on the host, nobody is asked. */
class SandboxFallbackOffTest {

    @TempDir
    Path base;

    @Test
    void aToolCallErrorsWhenTheContainerSandboxIsDownAndTheFallbackIsOff() {
        Rig rig = new Rig(base);
        rig.sandbox(new LocalSandboxExecutor(new org.kendar.llm.harness.sandbox.ChatDirLayout(base)));
        AtomicInteger runs = new AtomicInteger();
        rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> {
            runs.incrementAndGet();
            throw new SandboxUnavailableException("Docker sandbox unavailable: cannot connect");
        }));
        String chatId = rig.newChat();
        rig.model.enqueue(toolUse("b1", "bash", "{\"x\":\"ls\"}"), text("done"));

        rig.loop.run(chatId, "run", new Events(), () -> false, null);

        ArrayNode m = rig.model.requests.get(1).messages();
        JsonNode tr = m.get(m.size() - 1).get("content").get(0);
        assertTrue(tr.get("is_error").asBoolean(), tr.toString());
        assertTrue(tr.get("content").asText().contains("Docker sandbox unavailable"), tr.toString());
        assertFalse(rig.sandbox.localFallbackActive(chatId));
        assertEquals(1, runs.get(), "not retried on the host");
    }
}

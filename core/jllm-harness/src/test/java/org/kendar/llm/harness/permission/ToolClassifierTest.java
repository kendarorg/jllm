package org.kendar.llm.harness.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.permission.ToolClassifier.Verdict;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ToolClassifierTest {

    @TempDir
    Path base;

    Rig rig;
    String chat;
    ScriptedModel fast = new ScriptedModel();
    AtomicInteger executions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        rig.loop = rig.loopWith(rig.model, new ToolClassifier(fast, MAPPER, 0.8, 5));
        chat = rig.newChat();
        rig.sessions.setMode(chat, PermissionMode.AUTO);
        rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> {
            executions.incrementAndGet();
            return "ran";
        }));
        rig.model.enqueue(toolUse("t1", "bash", "{\"x\":\"ls\"}"), text("done"));
    }

    private Events run(boolean approve) {
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) rig.approvals.resolve(chat, data.get("id").asText(), approve, null);
        };
        rig.loop.run(chat, "list files", ev, () -> false);
        return ev;
    }

    @Test
    void safeRunsWithoutAsking() {
        fast.simpleReplies.add("{\"verdict\":\"safe\",\"confidence\":0.95}");
        Events ev = run(true);
        assertEquals(1, executions.get());
        assertTrue(ev.ofType("approval_request").isEmpty());
        assertEquals("SAFE", ev.ofType("classifier").get(0).get("verdict").asText());
        assertTrue(fast.simpleUserPrompts.get(0).contains("list files"));
    }

    @Test
    void unsafeAsksAndRespectsTheAnswer() {
        fast.simpleReplies.add("{\"verdict\":\"unsafe\",\"confidence\":0.99}");
        Events ev = run(false);
        assertEquals(0, executions.get());
        assertEquals(1, ev.ofType("approval_request").size());
    }

    @Test
    void failureLowConfidenceAndGarbageFailClosed() {
        ToolClassifier c = new ToolClassifier(fast, MAPPER, 0.8, 5);
        assertEquals(Verdict.UNSURE, c.parse("{\"verdict\":\"safe\",\"confidence\":0.5}"));
        assertEquals(Verdict.UNSURE, c.parse("sure, go ahead"));
        assertEquals(Verdict.SAFE, c.parse("```json\n{\"verdict\":\"safe\",\"confidence\":0.9}\n```"));

        // no scripted reply -> the fast model throws -> ask
        Events ev = run(true);
        assertEquals(1, ev.ofType("approval_request").size());
        assertEquals(1, executions.get());
    }

    @Test
    void withoutAClassifierAutoNeverAsks() {
        rig.loop = rig.loopWith(rig.model);
        Events ev = run(false);
        assertEquals(1, executions.get());
        assertFalse(ev.types().contains("approval_request"));
    }
}

package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.FileSystemAgentTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class AgentLoopModesTest {

    @TempDir
    Path base;

    Rig rig;
    String chatId;

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chatId = rig.newChat();
    }

    private void seedHistory() {
        ArrayNode m = MAPPER.createArrayNode();
        for (int i = 1; i <= 3; i++) {
            m.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", "old" + i);
            m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", "r" + i);
        }
        rig.sessions.saveMessages(chatId, m);
    }

    @Test
    void planModeWritesToScratchAndRefusesTheWorkDir() throws Exception {
        rig.register(new FileSystemAgentTools().tools().toArray(new org.kendar.llm.harness.tools.HarnessTool[0]));
        rig.sessions.setMode(chatId, PermissionMode.PLAN);
        Path work = rig.sandbox.workDir(chatId);
        Path scratch = rig.sandbox.scratchDir(chatId, org.kendar.llm.harness.agent.AgentLoop.scratchId(rig.sessions.get(chatId).agentId()));
        String abs = work.resolve("project.txt").toString().replace("\\", "\\\\");

        rig.model.enqueue(toolUse("w1", "write_file", "{\"path\":\"plan.md\",\"content\":\"p\"}"),
                text("a"));
        Events ev = new Events();
        rig.loop.run(chatId, "plan it", ev, () -> false);
        assertEquals("p", Files.readString(scratch.resolve("plan.md")));
        assertFalse(Files.exists(work.resolve("plan.md")));
        assertEquals(1, ev.ofType("plan_ready").size());

        rig.model.enqueue(toolUse("w2", "write_file", "{\"path\":\"" + abs + "\",\"content\":\"x\"}"), text("b"));
        rig.loop.run(chatId, "again", new Events(), () -> false);
        assertFalse(Files.exists(work.resolve("project.txt")));
        var last = rig.model.requests.get(rig.model.requests.size() - 1).messages();
        assertTrue(last.get(last.size() - 1).toString().contains("read-only"), last.toString());
    }

    @Test
    void modeIsInTheAddendumNotInTheCachedPrefix() {
        rig.model.enqueue(text("a"), text("b"));
        rig.sessions.setMode(chatId, PermissionMode.PLAN);
        rig.loop.run(chatId, "one", new Events(), () -> false);
        rig.sessions.setMode(chatId, PermissionMode.EDIT);
        rig.loop.run(chatId, "two", new Events(), () -> false);

        var first = rig.model.requests.get(0);
        var second = rig.model.requests.get(1);
        assertEquals(first.pinned(), second.pinned());
        assertTrue(first.extra().contains("Mode: PLAN"));
        assertTrue(second.extra().contains("Mode: EDIT"));
    }

    @Test
    void rejectedAsTooLongIsCompactedAndRetriedOnce() {
        seedHistory();
        AtomicInteger calls = new AtomicInteger();
        rig.model.simpleReplies.add("SUMMARY");
        rig.model.handler = r -> {
            if (calls.incrementAndGet() == 1) throw new ModelException("prompt is too long: 999999 tokens");
            return text("fine");
        };
        Events ev = new Events();

        rig.loop.run(chatId, "go", ev, () -> false);

        assertEquals(2, calls.get());
        assertTrue(ev.ofType("error").isEmpty());
        assertEquals("overflow", ev.ofType("compaction").get(0).get("reason").asText());
        assertTrue(rig.sessions.get(chatId).messagesJson().contains("SUMMARY"));
    }

    @Test
    void secondRejectionIsAnError() {
        seedHistory();
        rig.model.simpleReplies.add("SUMMARY");
        rig.model.handler = r -> {
            throw new ModelException("prompt is too long");
        };
        Events ev = new Events();

        rig.loop.run(chatId, "go", ev, () -> false);

        assertEquals(1, ev.ofType("error").size());
    }

    @Test
    void autoOffReportsTheOverflowInsteadOfCompacting() {
        rig.configure(c -> c.withCompaction(new HarnessConfig.Compaction(false, 15_000, null)));
        chatId = rig.newChat();
        seedHistory();
        AtomicInteger calls = new AtomicInteger();
        rig.model.handler = r -> {
            calls.incrementAndGet();
            throw new ModelException("prompt is too long");
        };
        Events ev = new Events();

        rig.loop.run(chatId, "go", ev, () -> false);

        assertEquals(1, calls.get());
        assertEquals(1, ev.ofType("error").size());
    }
}

package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** useContextStack: a sub-agent continues the parent's request prefix instead of building its own. */
class SubAgentContextStackTest {

    @TempDir
    Path base;

    private static boolean isSub(Recorded r) {
        return r.messages().toString().contains("Task:");
    }

    private Rig rig(String type) {
        Rig rig = new Rig(base).configure(c -> c.withUseContextStack(true));
        rig.register(tool("search", "Misc", ToolKind.READ_ONLY, (c, in) -> "found"),
                tool("writer", "Misc", ToolKind.FILE_EDIT, (c, in) -> "written"));
        rig.model.handler = r -> {
            boolean sub = r.messages().toString().contains("Task:");
            if (!sub) {
                return r.messages().size() == 1
                        ? toolUse("t1", "task", "{\"prompts\":[\"p\"],\"subagent_type\":\"" + type + "\"}") : text("fin");
            }
            return r.messages().size() == 1 ? toolUse("s", "writer", "{}") : text("done");
        };
        return rig;
    }

    @Test
    void subAgentRequestHasTheParentsPinnedExtraAndTools() {
        Rig rig = rig("general");
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.EDIT);

        rig.loop.run(chatId, "go", new Events(), () -> false);

        Recorded main = rig.model.requests.stream().filter(r -> !isSub(r)).findFirst().orElseThrow();
        Recorded sub = rig.model.requests.stream().filter(SubAgentContextStackTest::isSub).findFirst().orElseThrow();
        assertEquals(main.pinned(), sub.pinned());
        assertEquals(main.extra(), sub.extra());
        assertEquals(main.toolNames(), sub.toolNames());
        assertTrue(sub.messages().get(0).toString().contains("Task:"), "the role text and the task are in the first message");
    }

    @Test
    void parentContextIsUntouchedAfterTheSubAgentReturns() {
        Rig rig = rig("general");
        String chatId = rig.newChat();

        rig.loop.run(chatId, "go", new Events(), () -> false);

        var mains = rig.model.requests.stream().filter(r -> !isSub(r)).toList();
        assertEquals(2, mains.size());
        // second main request = first one + assistant(task call) + tool_result: the earlier prefix is unchanged
        assertEquals(mains.get(0).messages().get(0), mains.get(1).messages().get(0));
        assertEquals(mains.get(0).pinned(), mains.get(1).pinned());
        assertEquals(mains.get(0).extra(), mains.get(1).extra());
        assertEquals(mains.get(0).toolNames(), mains.get(1).toolNames());
        assertEquals(3, mains.get(1).messages().size());
        assertEquals(4, rig.sessions.messages(rig.sessions.get(chatId)).size(), "sub-agent messages stay out of the chat");
    }

    @Test
    void readOnlyTypeCannotCallWriteToolsEvenThoughTheyAreOffered() {
        Rig rig = rig("plan");
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.EDIT);

        rig.loop.run(chatId, "go", new Events(), () -> false);

        var subs = rig.model.requests.stream().filter(SubAgentContextStackTest::isSub).toList();
        assertEquals(2, subs.size());
        assertTrue(subs.get(0).toolNames().containsAll(List.of("search", "writer", "task")));
        var last = subs.get(1).messages();
        String result = last.get(last.size() - 1).toString();
        assertTrue(result.contains("Tool 'writer' is not available to this sub-agent"), result);
    }
}

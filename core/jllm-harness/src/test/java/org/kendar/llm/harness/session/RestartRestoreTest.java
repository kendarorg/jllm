package org.kendar.llm.harness.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** A rebuilt harness over the same root sends the same request as one that never stopped. */
class RestartRestoreTest {

    @TempDir
    Path base;

    private Rig rig(ChatSessionStore store) {
        Rig r = new Rig(base, store);
        r.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> "r"));
        return r;
    }

    @Test
    void chatRowAndNextRequestSurviveARestart() {
        // control: never restarted
        Rig control = rig(new FileChatSessionStore(base.resolve("control"), MAPPER));
        String cc = control.newChat();
        control.model.enqueue(toolUse("t1", "echo", "{}"), text("a1"), text("a2"));
        control.loop.run(cc, "q1", new Events(), () -> false);
        control.loop.run(cc, "q2", new Events(), () -> false);

        // restarted between the two turns
        Path root = base.resolve("restart");
        Rig first = rig(new FileChatSessionStore(root, MAPPER));
        String id = first.newChat();
        first.sessions.setMode(id, PermissionMode.PLAN);
        first.sessions.setToolGroups(id, Set.of("Misc"));
        first.sessions.addUsage(id, new Usage(10, 20, 30, 40));
        first.model.enqueue(toolUse("t1", "echo", "{}"), text("a1"));
        first.loop.run(id, "q1", new Events(), () -> false);

        Rig second = rig(new FileChatSessionStore(root, MAPPER));
        ChatSessionData back = second.sessions.get(id);
        assertEquals("PLAN", back.mode());
        assertEquals(Set.of("Misc"), second.sessions.toolGroups(id));
        assertTrue(back.inputTokens() >= 10);
        second.model.enqueue(text("a2"));
        second.loop.run(id, "q2", new Events(), () -> false);

        var req = second.model.requests.get(0);
        var ref = control.model.requests.get(control.model.requests.size() - 1);
        assertEquals(ref.messages().toString(), req.messages().toString());
        assertEquals(second.sessions.pinnedPrefix(back), req.pinned());
    }
}

package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.plugin.OneShotModel;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class AgentTaskApprovalTest {
    @TempDir
    Path base;

    private AgentOneShotModel model(Rig rig, AgentDefinition def) {
        return new AgentOneShotModel(new AgentCatalog(List.of(def)), rig.model, () -> rig.loop, rig.sessions);
    }

    private static OneShotModel.Call call(String agent, String user, Set<String> approved) {
        return new OneShotModel.Call(agent, "", user, "ann", "flow: t 1", approved);
    }

    @Test
    void agentWithToolsRunsTheLoopOnceAndUsesItsTool() {
        Rig rig = new Rig(base);
        AtomicInteger calls = new AtomicInteger();
        rig.registered.add(tool("lookup", "T", ToolKind.READ_ONLY, (c, i) -> {
            calls.incrementAndGet();
            return "42";
        }));
        rig.model.enqueue(toolUse("t1", "lookup", "{}"), text("the answer is 42"));
        var def = new AgentDefinition("finder", "d", List.of("lookup"), "Find things.");
        assertEquals("the answer is 42", model(rig, def).complete(call("finder", "what is it?", Set.of())));
        assertEquals(1, calls.get());
    }

    @Test
    void approvalNeededFailsTheTaskWithoutRunningTheTool() {
        Rig rig = new Rig(base);
        AtomicInteger calls = new AtomicInteger();
        rig.registered.add(tool("pay", "T", ToolKind.FILE_EDIT, (c, i) -> {
            calls.incrementAndGet();
            return "paid";
        }));
        rig.requiresApproval.put("pay", true);
        rig.model.enqueue(toolUse("t1", "pay", "{}"), text("done"));
        var def = new AgentDefinition("payer", "d", List.of("pay"), "Pay.");
        var ex = assertThrows(OneShotModel.ApprovalRequiredException.class,
                () -> model(rig, def).complete(call("payer", "pay now", Set.of())));
        assertTrue(ex.getMessage().contains("pay"), ex.getMessage());
        assertEquals(List.of("pay"), ex.tools());
        assertEquals(0, calls.get(), "an unapproved tool never runs");
    }

    @Test
    void toolApprovedByTheCallerRunsAndTheTaskChatBelongsToTheUser() {
        Rig rig = new Rig(base);
        AtomicInteger calls = new AtomicInteger();
        rig.registered.add(tool("pay", "T", ToolKind.FILE_EDIT, (c, i) -> {
            calls.incrementAndGet();
            return "paid";
        }));
        rig.requiresApproval.put("pay", true);
        rig.model.enqueue(toolUse("t1", "pay", "{}"), text("done"));
        var def = new AgentDefinition("payer", "d", List.of("pay"), "Pay.");
        assertEquals("done", model(rig, def).complete(call("payer", "pay now", Set.of("pay"))));
        assertEquals(1, calls.get());
        var mine = rig.sessions.list("ann");
        assertEquals(1, mine.size());
        assertEquals("flow: t 1", mine.get(0).title());
    }
}

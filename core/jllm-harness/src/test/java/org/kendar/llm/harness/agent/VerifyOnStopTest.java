package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** STOP hooks can veto the stop: the agent is told why and goes on, a bounded number of times. */
class VerifyOnStopTest {

    @TempDir
    Path base;
    Rig rig;
    String chatId;
    final List<HookContext> seen = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chatId = rig.newChat();
    }

    /** Installs {@code hooks} (plus one that records every STOP) and rebuilds the loop. */
    private void hooks(Hooks hooks) {
        hooks.add(HookEvent.STOP, ctx -> {
            seen.add(ctx);
            return HookResult.allow();
        });
        rig.hooks = hooks;
        rig.loop = rig.loopWith(rig.model);
    }

    private static List<String> verifyNotes(ArrayNode log) {
        List<String> out = new ArrayList<>();
        for (JsonNode m : log) {
            if (!"user".equals(m.path("role").asText())) continue;
            String t = m.path("content").path(0).path("text").asText("");
            if (t.startsWith(AgentLoop.VERIFY_NOTE)) out.add(t.substring(AgentLoop.VERIFY_NOTE.length()).strip());
        }
        return out;
    }

    @Test
    void aHookThatFailsTwiceThenPassesSendsTheAgentBackTwice() {
        Hooks h = new Hooks();
        h.add(HookEvent.STOP, ctx -> ctx.attempt() <= 2 ? HookResult.continueWith("fail " + ctx.attempt())
                : HookResult.allow());
        hooks(h);
        rig.model.enqueue(text("a"), text("b"), text("c"));
        Events ev = new Events();

        assertEquals(AgentLoop.Outcome.COMPLETED, rig.loop.run(chatId, "go", ev, () -> false));

        assertEquals(List.of("fail 1", "fail 2"), verifyNotes(rig.sessions.messages(rig.sessions.get(chatId))));
        assertEquals(2, ev.ofType("verify_failed").size());
        assertEquals(HookContext.MAIN, ev.ofType("verify_failed").get(0).path("agent").asText());
        // observers see exactly one STOP per attempt, with the agent and the attempt number
        assertEquals(List.of(1, 2, 3), seen.stream().map(HookContext::attempt).toList());
        assertTrue(seen.stream().allMatch(c -> HookContext.MAIN.equals(c.agent())));
        assertEquals("c", seen.get(2).text());
    }

    @Test
    void aHookThatAlwaysFailsEndsUnverifiedAfterMaxReentries() {
        Hooks h = new Hooks();
        h.add(HookEvent.STOP, ctx -> HookResult.continueWith("fail " + ctx.attempt()));
        hooks(h);
        for (int i = 0; i < 10; i++) rig.model.enqueue(text("t" + i));
        Events ev = new Events();

        assertEquals(AgentLoop.Outcome.UNVERIFIED, rig.loop.run(chatId, "go", ev, () -> false));
        // default maxReentries 3: the first attempt and three re-entries
        assertEquals(4, rig.model.requests.size());
        assertEquals(3, ev.ofType("verify_failed").size());
    }

    @Test
    void theSameFailureTwiceEndsUnverifiedEarly() {
        Hooks h = new Hooks();
        h.add(HookEvent.STOP, ctx -> HookResult.continueWith("the same error"));
        hooks(h);
        for (int i = 0; i < 10; i++) rig.model.enqueue(text("t" + i));
        Events ev = new Events();

        assertEquals(AgentLoop.Outcome.UNVERIFIED, rig.loop.run(chatId, "go", ev, () -> false));
        assertEquals(2, rig.model.requests.size());
        assertEquals("verify_repeat", ev.ofType("stuck").get(0).path("pattern").asText());
    }

    @Test
    void theReentryMessageIsAppendedAtTheTailOnly() {
        Hooks h = new Hooks();
        h.add(HookEvent.STOP, ctx -> ctx.attempt() == 1 ? HookResult.continueWith("x") : HookResult.allow());
        hooks(h);
        rig.model.enqueue(text("a"), text("b"));
        rig.loop.run(chatId, "go", new Events(), () -> false);

        ArrayNode first = rig.model.requests.get(0).messages();
        ArrayNode second = rig.model.requests.get(1).messages();
        for (int i = 0; i < first.size(); i++) assertEquals(first.get(i), second.get(i), "prefix changed at " + i);
        assertEquals(first.size() + 2, second.size()); // the answer, then the re-entry message
        assertEquals(rig.model.requests.get(0).extra(), rig.model.requests.get(1).extra());
        assertEquals(rig.model.requests.get(0).toolNames(), rig.model.requests.get(1).toolNames());
    }

    @Test
    void planModeIsNotChecked() {
        Hooks h = new Hooks();
        h.add(HookEvent.STOP, ctx -> HookResult.continueWith("never"));
        hooks(h);
        seen.clear();
        rig.sessions.setMode(chatId, PermissionMode.PLAN);
        rig.model.enqueue(text("plan"));
        assertEquals(AgentLoop.Outcome.COMPLETED, rig.loop.run(chatId, "go", new Events(), () -> false));
        assertTrue(seen.isEmpty());
    }

    @Test
    void aSubAgentGetsStopWithItsLabelAndReportsUnverified() {
        Hooks h = new Hooks();
        h.add(HookEvent.STOP, ctx -> HookResult.continueWith("still broken " + ctx.attempt()));
        hooks(h);
        for (int i = 0; i < 10; i++) rig.model.enqueue(text("answer " + i));

        AgentLoop.SubAgentIncompleteException e = assertThrows(AgentLoop.SubAgentIncompleteException.class,
                () -> rig.loop.runSubAgent(chatId, null, "fix it"));
        assertEquals("unverified", e.status());
        assertEquals("answer 3", e.partial());
        assertTrue(seen.get(0).agent().matches(".+ #\\d+"), seen.get(0).agent());
    }

    @Test
    void changedInRunFollowsToolsThatChangeThings() {
        rig.register(tool("look", "G", ToolKind.READ_ONLY, (c, in) -> "seen"));
        rig.register(tool("edit", "G", ToolKind.FILE_EDIT, (c, in) -> "edited"));
        boolean[] changed = new boolean[2];
        // the recording hook first: seen.size() is the run number when the second hook runs
        Hooks ordered = new Hooks();
        ordered.add(HookEvent.STOP, ctx -> {
            seen.add(ctx);
            return HookResult.allow();
        });
        ordered.add(HookEvent.STOP, ctx -> {
            changed[seen.size() - 1] = rig.loop.changedInRun(ctx.chatId(), ctx.agent());
            return HookResult.allow();
        });
        rig.hooks = ordered;
        rig.loop = rig.loopWith(rig.model);
        rig.model.enqueue(toolUse("1", "look", "{}"), text("only looked"), toolUse("2", "edit", "{}"), text("edited"));

        rig.loop.run(chatId, "look", new Events(), () -> false);
        rig.loop.run(chatId, "edit", new Events(), () -> false);

        assertFalse(changed[0]);
        assertTrue(changed[1]);
        assertFalse(rig.loop.changedInRun(chatId, HookContext.MAIN)); // cleared once the run ended
    }

    @Test
    void continueWithMessagesAreJoinedAndADenialStillWins() {
        Hooks h = new Hooks();
        h.add(HookEvent.STOP, ctx -> HookResult.continueWith("a"));
        h.add(HookEvent.STOP, ctx -> HookResult.addContext("ignored"));
        h.add(HookEvent.STOP, ctx -> HookResult.continueWith("b"));
        assertEquals("a\n\nb", h.run(HookContext.stop("c", HookContext.MAIN, "x", 1)).continueWith());

        Hooks d = new Hooks();
        d.add(HookEvent.STOP, ctx -> HookResult.deny("no"));
        d.add(HookEvent.STOP, ctx -> HookResult.continueWith("never"));
        HookResult r = d.run(HookContext.stop("c", HookContext.MAIN, "x", 1));
        assertTrue(r.denied());
        assertFalse(r.continues());
    }
}

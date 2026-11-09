package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/**
 * The cache boundary: the pinned chatId+agentId system block (and the policy block after it) must be
 * byte-identical on every request, including those sent after compaction rewrote the message log.
 */
class CacheBoundaryTest {

    @TempDir
    Path base;

    Rig rig;
    String chatId;

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        rig.configure(c -> c.withContextBudgetTokens(1000));
        rig.configure(c -> c.withKeepRecentTurns(1));
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> "r"));
        chatId = rig.newChat();
    }

    private void assertPinnedStable(int expectedRequests) {
        List<Recorded> reqs = rig.model.requests;
        assertEquals(expectedRequests, reqs.size());
        byte[] pinned0 = reqs.get(0).pinned().getBytes(StandardCharsets.UTF_8);
        byte[] extra0 = reqs.get(0).extra().getBytes(StandardCharsets.UTF_8);
        for (Recorded r : reqs) {
            assertArrayEquals(pinned0, r.pinned().getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(extra0, r.extra().getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("chatId=" + chatId + "; agentId=" + rig.sessions.get(chatId).agentId(), reqs.get(0).pinned());
        assertEquals(rig.sessions.pinnedPrefix(rig.sessions.get(chatId)), reqs.get(0).pinned());
        // tool schema order is part of the cached prefix too
        for (Recorded r : reqs) assertEquals(reqs.get(0).toolNames(), r.toolNames());
    }

    @Test
    void pinnedPrefixIsIdenticalAcrossTurnsAndTier2Compaction() {
        // the tier-2 summary is a request of its own: same prefix, plus the summary instruction
        rig.model.enqueue(toolUse("t1", "echo", "{}"), text("a1"), text("SUMMARY"), text("a2"));
        Events e1 = new Events();
        rig.loop.run(chatId, "q1 " + "p".repeat(6000), e1, () -> false);
        rig.sessions.setTitle(chatId, "renamed mid-conversation"); // title/mode changes must not leak into the prefix
        Events e2 = new Events();

        rig.loop.run(chatId, "q2", e2, () -> false);

        assertTrue(e1.ofType("compaction").isEmpty(), "single turn: nothing to cut yet");
        assertEquals(2, e2.ofType("compaction").get(0).get("tier").asInt());
        // the message log really was rewritten between request 2 and 3 ...
        String firstBefore = rig.model.requests.get(1).messages().get(0).toString();
        String firstAfter = rig.model.requests.get(3).messages().get(0).toString();
        assertNotEquals(firstBefore, firstAfter);
        assertTrue(firstAfter.contains("SUMMARY"));
        // ... but the pinned prefix did not move
        assertPinnedStable(4);
    }

    @Test
    void pinnedPrefixIsIdenticalAcrossTier3ApprovedRewrite() {
        // request 2 is the tier-2 summary request; it stays over budget -> tier 3 (plain text request)
        rig.model.enqueue(toolUse("t1", "echo", "{}"), text("a1"), text("x".repeat(5000)), text("a2"));
        rig.model.simpleReplies.add("REWRITE");
        rig.loop.run(chatId, "q1 " + "p".repeat(6000), new Events(), () -> false);
        Events e2 = new Events();
        e2.also = (type, data) -> {
            if ("approval_request".equals(type)) {
                rig.approvals.resolve(chatId, data.get("id").asText(), true, null);
            }
        };

        rig.loop.run(chatId, "q2", e2, () -> false);

        assertEquals(3, e2.ofType("compaction").get(e2.ofType("compaction").size() - 1).get("tier").asInt());
        assertTrue(rig.model.requests.get(3).messages().get(0).toString().contains("REWRITE"));
        assertPinnedStable(4);
    }

    @Test
    void pinnedPrefixIsIdenticalWhenTier3IsDeclined() {
        rig.model.enqueue(toolUse("t1", "echo", "{}"), text("a1"), text("x".repeat(5000)), text("a2"));
        rig.model.simpleReplies.add("REWRITE");
        rig.loop.run(chatId, "q1 " + "p".repeat(6000), new Events(), () -> false);
        Events e2 = new Events();
        e2.also = (type, data) -> {
            if ("approval_request".equals(type)) {
                rig.approvals.resolve(chatId, data.get("id").asText(), false, null);
            }
        };

        rig.loop.run(chatId, "q2", e2, () -> false);

        assertTrue(e2.ofType("compaction").stream().noneMatch(n -> n.get("tier").asInt() == 3));
        assertFalse(rig.model.requests.get(3).messages().toString().contains("REWRITE"));
        assertPinnedStable(4);
    }

    @Test
    void pinnedPrefixDiffersBetweenChatsButIsDeterministicPerChat() {
        String other = rig.newChat();

        String a = rig.sessions.pinnedPrefix(rig.sessions.get(chatId));
        String b = rig.sessions.pinnedPrefix(rig.sessions.get(other));

        assertNotEquals(a, b);
        assertEquals(a, rig.sessions.pinnedPrefix(rig.sessions.get(chatId)));
        assertTrue(Arrays.equals(a.getBytes(StandardCharsets.UTF_8),
                rig.sessions.pinnedPrefix(rig.sessions.get(chatId)).getBytes(StandardCharsets.UTF_8)));
    }
}

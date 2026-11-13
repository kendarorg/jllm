package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Mask;
import org.kendar.llm.harness.session.SubAgentRunLog;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.*;

/** Decisions are validated one by one; idle main agents leave memory; the main agent of a chat is looked up by key. */
class MnemonicValidationTest {

    private final InMemoryMnemonicStore store = new InMemoryMnemonicStore();
    private final long[] now = {1_000_000L};

    private MnemonicStrategy strategy(String mainKey, ArrayNode mainLog) {
        MnemonicStrategy s = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), store, SubAgentRunLog.noop(),
                mainKey == null ? null : chat -> mainKey, mainLog == null ? null : chat -> mainLog);
        s.clock = () -> now[0];
        return s;
    }

    private static ArrayNode pairs(int n) {
        ArrayNode log = log();
        user(log, "Read all the files and report");
        for (int i = 0; i < n; i++) readPair(log, i);
        return log;
    }

    private static List<Integer> masked(MnemonicState st) {
        TreeSet<Integer> out = new TreeSet<>();
        for (Mask m : st.masks) out.add(m.index());
        return List.copyOf(out);
    }

    private static ContextTurn turnOf(String chat, String agent, ArrayNode log) {
        return new ContextTurn(chat, agent, log, null, null, null, null, null, null, true);
    }

    @Test
    void anEditInPlaceDropsOnlyTheDecisionsAboutThatMessage() {
        MnemonicStrategy s = strategy(null, null);
        ArrayNode log = pairs(15);
        s.prepare(turn(log, null));
        List<Integer> before = masked(s.state(CHAT, AGENT));
        assertTrue(before.size() >= 3, before.toString());
        int edited = before.get(1);

        ((ObjectNode) log.get(edited).get("content").get(0)).put("content", "edited in place");
        s.contextTokens(CHAT, AGENT, log); // validates, masks nothing new

        List<Integer> after = masked(s.state(CHAT, AGENT));
        assertFalse(after.contains(edited));
        assertEquals(before.size() - 1, after.size(), "only the edited message lost its masks");
        assertTrue(s.state(CHAT, AGENT).frontier <= edited, "masking may start again at the edited message");
    }

    @Test
    void anInsertionDropsEveryLaterDecision() {
        MnemonicStrategy s = strategy(null, null);
        ArrayNode log = pairs(15);
        s.prepare(turn(log, null));
        List<Integer> before = masked(s.state(CHAT, AGENT));
        int at = before.get(1);

        log.insert(at, MAPPER.createObjectNode().put("role", "user").put("content", "inserted"));
        s.contextTokens(CHAT, AGENT, log);

        assertEquals(before.subList(0, 1), masked(s.state(CHAT, AGENT)));
    }

    @Test
    void anIdleMainAgentLeavesMemoryAndComesBackFromTheStore() {
        MnemonicStrategy s = strategy(null, null);
        ArrayNode log = pairs(15);
        s.prepare(turn(log, null));
        List<Integer> before = masked(s.state(CHAT, AGENT));
        assertFalse(before.isEmpty());
        assertEquals(1, s.agentsInMemory());

        now[0] += 31 * 60_000L;
        s.prepare(turnOf("c2", AGENT, pairs(2)));

        assertEquals(1, s.agentsInMemory(), "the idle agent of the first chat is still in memory");
        assertEquals(before, masked(s.state(CHAT, AGENT)), "its decisions come back from the store");
    }

    @Test
    void anAgentInUseIsNotEvicted() {
        MnemonicStrategy s = strategy(null, null);
        s.prepare(turn(pairs(2), null));
        now[0] += 29 * 60_000L;
        s.prepare(turnOf("c2", AGENT, pairs(2)));
        assertEquals(2, s.agentsInMemory());
    }

    @Test
    void theLeastRecentlyUsedMainAgentsGoOverTheCap() {
        MnemonicStrategy s = strategy(null, null);
        s.maxAgents = 2;
        for (int i = 0; i < 4; i++) {
            s.prepare(turnOf("chat" + i, AGENT, pairs(2)));
            now[0] += 2 * 60_000L;
        }
        assertEquals(2, s.agentsInMemory());
        assertNotNull(s.agentFor("chat3", AGENT));
        assertNull(s.agentFor("chat0", AGENT));
    }

    @Test
    void theMainAgentOfAChatIsTheSessionsCurrentOne() {
        MnemonicStrategy s = strategy("second", null);
        s.prepare(turnOf(CHAT, "first", pairs(2)));
        s.prepare(turnOf(CHAT, "second", pairs(3)));

        assertSame(s.agentFor(CHAT, "second"), s.agentFor(CHAT, null));
        assertSame(s.agentFor(CHAT, "second"), s.agentFor(CHAT, "unknown-sub-agent"));
    }

    @Test
    void recallFindsTheLogOfAnAgentThatLeftMemory() {
        ArrayNode log = pairs(15);
        MnemonicStrategy s = strategy(AGENT, log);
        s.prepare(turn(log, null));
        now[0] += 31 * 60_000L;
        s.prepare(turnOf("c2", AGENT, pairs(2)));
        assertEquals(1, s.agentsInMemory());

        MnemonicStrategy.Agent a = s.agentFor(CHAT, null);

        assertSame(log, a.log);
        assertFalse(a.state.masks.isEmpty());
    }
}

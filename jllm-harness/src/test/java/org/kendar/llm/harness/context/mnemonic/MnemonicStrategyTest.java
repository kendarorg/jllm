package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.HarnessTestSupport.Recorded;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.ContextView;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Mask;
import org.kendar.llm.harness.session.SubAgentRunLog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.text;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.*;

class MnemonicStrategyTest {

    InMemoryMnemonicStore store;
    MnemonicStrategy strategy;

    @BeforeEach
    void setUp() {
        store = new InMemoryMnemonicStore();
        strategy = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), store, SubAgentRunLog.noop());
    }

    private ArrayNode pairs(int n) {
        ArrayNode log = log();
        user(log, "Read all the files and report");
        for (int i = 0; i < n; i++) readPair(log, i);
        return log;
    }

    @Test
    void belowTheTriggerTheViewIsTheLog() {
        ArrayNode log = pairs(5);
        ContextView v = strategy.prepare(turn(log, null));
        assertFalse(v.changed());
        assertFalse(v.logChanged());
        assertEquals(log.toString(), v.messages().toString());
    }

    @Test
    void aBatchMasksTheOldestToolOutputsAndLeavesTheLogVerbatim() {
        ArrayNode log = pairs(15);
        String before = log.toString();
        ContextView v = strategy.prepare(turn(log, null));

        assertTrue(v.changed());
        assertFalse(v.logChanged(), "the log is never rewritten");
        assertEquals(before, log.toString());
        assertEquals(log.size(), v.messages().size());
        List<String> receipts = receipts(v.messages());
        assertFalse(receipts.isEmpty());
        assertTrue(receipts.get(0).startsWith("[masked: read_file(path=src/F0.java) -> ~"), receipts.get(0));
        assertTrue(receipts.get(0).contains("recall id=r"));
        // oldest first, protected tail untouched
        JsonNode lastResult = v.messages().get(log.size() - 1).path("content").path(0);
        assertEquals(fileText(14), lastResult.path("content").asText());
        assertTrue(Messages.tokens(v.messages()) <= 5100 + 1000, "down to about the target");
        MnemonicState st = strategy.state(CHAT, AGENT);
        assertEquals(st.masks.get(0).index(), st.frontier);
    }

    @Test
    void decisionsAreStickyAndTheViewOnlyGrowsByAppending() {
        ArrayNode log = pairs(15);
        String first = strategy.prepare(turn(log, null)).messages().toString();
        ContextView again = strategy.prepare(turn(log, null));
        assertFalse(again.changed());
        assertEquals(first, again.messages().toString(), "same log, same bytes");

        readPair(log, 15);
        ArrayNode grown = strategy.prepare(turn(log, null)).messages();
        String prefix = first.substring(0, first.length() - 1);
        assertTrue(grown.toString().startsWith(prefix), "earlier messages byte-identical: the prompt cache holds");
    }

    @Test
    void aReloadedStateRendersTheSameBytes() {
        ArrayNode log = pairs(15);
        String view = strategy.prepare(turn(log, null)).messages().toString();
        MnemonicStrategy fresh = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), store,
                SubAgentRunLog.noop());
        ContextView v = fresh.prepare(turn(log, null));
        assertFalse(v.changed());
        assertEquals(view, v.messages().toString());
    }

    @Test
    void theFrontierOnlyMovesForward() {
        ArrayNode log = pairs(15);
        strategy.prepare(turn(log, null));
        MnemonicState st = strategy.state(CHAT, AGENT);
        List<Mask> firstBatch = List.copyOf(st.masks);
        int frontier = st.frontier;
        for (int i = 15; i < 30; i++) {
            readPair(log, i);
            strategy.prepare(turn(log, null));
        }
        assertTrue(st.masks.size() > firstBatch.size(), "a second batch happened");
        assertEquals(firstBatch, st.masks.subList(0, firstBatch.size()));
        for (Mask m : st.masks.subList(firstBatch.size(), st.masks.size())) assertTrue(m.index() >= frontier);
        assertTrue(st.frontier >= frontier);
    }

    @Test
    void aBatchBelowTheMinimumIsNotCommitted() {
        // one big old result over the trigger but freeing less than the 3.4k minimum batch
        strategy = new MnemonicStrategy(config().withMnemonic(new org.kendar.llm.harness.HarnessConfig.Mnemonic(
                null, null, 9000, 100, 1000, 1, null, 300, 300, 2000, true)), MAPPER, HarnessMetrics.noop(), store,
                SubAgentRunLog.noop());
        ArrayNode log = pairs(11);
        ContextView v = strategy.prepare(turn(log, null));
        assertFalse(v.changed());
        assertTrue(strategy.state(CHAT, AGENT).masks.isEmpty());
    }

    @Test
    void largeToolInputsBecomeReceiptsAndStayValidJson() {
        ArrayNode log = log();
        user(log, "write the files");
        for (int i = 0; i < 12; i++) {
            ObjectNode a = log.addObject().put("role", "assistant");
            ObjectNode use = a.putArray("content").addObject().put("type", "tool_use").put("id", "w" + i)
                    .put("name", "write_file");
            use.putObject("input").put("path", "out/" + i + ".txt").put("content", fileText(i));
            log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                    .put("tool_use_id", "w" + i).put("content", "ok");
        }
        ArrayNode view = strategy.prepare(turn(log, null)).messages();
        JsonNode input = view.get(1).path("content").path(0).path("input");
        assertTrue(input.isObject());
        assertEquals("out/0.txt", input.path("path").asText());
        assertTrue(input.path("content").asText().startsWith("[masked ~"), input.toString());
        assertTrue(input.path("content").asText().contains("recall id=a"));
    }

    @Test
    void reasoningIsDroppedOnlyFromEarlierUserTurns() {
        ArrayNode log = log();
        user(log, "first question");
        ObjectNode a = log.addObject().put("role", "assistant");
        ArrayNode c = a.putArray("content");
        c.addObject().put("type", "thinking").put("thinking", fileText(99)).put("signature", "sig");
        c.addObject().put("type", "text").put("text", "answer");
        user(log, "second question");
        for (int i = 0; i < 14; i++) {
            ObjectNode t = log.addObject().put("role", "assistant");
            ArrayNode tc = t.putArray("content");
            tc.addObject().put("type", "thinking").put("thinking", i == 0 ? fileText(50) : "short").put("signature", "s");
            ObjectNode use = tc.addObject().put("type", "tool_use").put("id", "t" + i).put("name", "read_file");
            use.putObject("input").put("path", "src/F" + i + ".java");
            log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                    .put("tool_use_id", "t" + i).put("content", fileText(i));
        }
        ArrayNode view = strategy.prepare(turn(log, null)).messages();
        assertEquals(1, view.get(1).path("content").size(), "old reasoning dropped");
        assertEquals("text", view.get(1).path("content").path(0).path("type").asText());
        assertEquals("thinking", view.get(3).path("content").path(0).path("type").asText(),
                "reasoning of the open tool chain is kept");
    }

    @Test
    void editingTheLogDropsTheDecisionsFromThatMessageOn() {
        ArrayNode log = pairs(15);
        strategy.prepare(turn(log, null));
        MnemonicState st = strategy.state(CHAT, AGENT);
        int masked = st.masks.size();
        assertTrue(masked > 2);
        int edited = st.masks.get(2).index();
        ((ObjectNode) log.get(edited).path("content").path(0)).put("content", "changed");
        strategy.prepare(turn(log, null));
        assertTrue(st.masks.stream().noneMatch(m -> m.index() == edited));
        assertTrue(st.masks.stream().anyMatch(m -> m.index() < edited));
    }

    @Test
    void aSegmentSwitchKeepsTheFirstRequestNotesAndATail() {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("## Objective\n- NOTES-123"));
        ArrayNode log = log();
        user(log, "The very first request");
        for (int i = 0; i < 9; i++) {
            assistant(log, "reply " + i + " " + fileText(i));
            user(log, "question " + i + " " + fileText(i + 100));
        }
        ContextView v = strategy.prepare(turn(log, prefix(model)));

        assertTrue(v.changed());
        MnemonicState st = strategy.state(CHAT, AGENT);
        assertEquals(1, st.segments.size());
        int cut = st.segments.get(0).cut();
        assertTrue(Messages.isPlainUserText(log.get(cut)));
        ArrayNode view = v.messages();
        assertEquals(2 + log.size() - cut, view.size());
        String head = view.get(0).toString();
        assertTrue(head.contains("The very first request"));
        assertTrue(head.contains("NOTES-123"));
        assertTrue(head.contains("history_recall"));
        assertEquals(Renderer.HANDOFF_ACK, view.get(1).path("content").path(0).path("text").asText());
        assertEquals(log.get(log.size() - 1).toString(), view.get(view.size() - 1).toString());

        // the notes request is the request as it stood plus the instruction, merged into the last user turn
        Recorded notes = model.requests.get(0);
        assertEquals("PIN", notes.pinned());
        assertEquals(log.size(), notes.messages().size());
        JsonNode lastBlocks = notes.messages().get(log.size() - 1).path("content");
        assertTrue(lastBlocks.get(lastBlocks.size() - 1).path("text").asText().contains("handoff notes"));
        assertEquals(300, notes.maxTokens());
    }

    @Test
    void compactDoesNothing() {
        ArrayNode log = pairs(15);
        String before = log.toString();
        CompactResult r = strategy.compactNow(turn(log, null), null);
        assertFalse(r.changed());
        assertEquals(MnemonicStrategy.NOT_USED, r.message());
        assertEquals(before, log.toString());
        assertTrue(strategy.state(CHAT, AGENT).masks.isEmpty());
    }

    @Test
    void overflowForcesABatchEvenBelowTheTrigger() {
        ArrayNode log = pairs(6);
        ContextView v = strategy.onOverflow(turn(log, null));
        assertNotNull(v);
        assertTrue(v.changed());
        assertFalse(receipts(v.messages()).isEmpty());
    }

    @Test
    void inheritedViewIsTheRenderedOne() {
        ArrayNode log = pairs(15);
        String view = strategy.prepare(turn(log, null)).messages().toString();
        assertEquals(view, strategy.inheritedView(CHAT, AGENT, log).toString());
    }

    @Test
    void subAgentStateIsDroppedOnRelease() {
        ArrayNode log = pairs(15);
        var sub = new org.kendar.llm.harness.context.ContextTurn(CHAT, "sub-1", log, null, null, null, null, null,
                null, false);
        strategy.prepare(sub);
        assertFalse(strategy.state(CHAT, "sub-1").masks.isEmpty());
        assertTrue(store.load(CHAT, "sub-1").masks.isEmpty(), "sub-agent decisions are not persisted");
        strategy.release(CHAT, "sub-1");
        assertTrue(strategy.state(CHAT, "sub-1").masks.isEmpty());
    }
}

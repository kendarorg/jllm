package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.context.TokenEstimate;

import static org.junit.jupiter.api.Assertions.*;

class CutPlannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void user(ArrayNode m, String text) {
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void toolRound(ArrayNode m, String id, String result) {
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "tool_use")
                .put("id", id).put("name", "grep");
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", id).put("content", result);
    }

    private static void thinking(ArrayNode m, boolean withText) {
        var c = m.addObject().put("role", "assistant").putArray("content");
        c.addObject().put("type", "thinking").put("thinking", "hmm").put("signature", "sig");
        if (withText) c.addObject().put("type", "text").put("text", "answer");
    }

    @Test
    void cutIndexEdgeSizes() {
        ArrayNode m = MAPPER.createArrayNode();
        assertEquals(0, CutPlanner.cutIndex(m, 1));
        user(m, "only");
        assertEquals(0, CutPlanner.cutIndex(m, 1));
        assertEquals(0, CutPlanner.cutIndex(m, 5), "not enough turns");
        assertEquals(0, CutPlanner.cutIndex(m, 0), "keepTurns is at least 1");
    }

    @Test
    void cutIndexNeverSplitsToolUseFromToolResult() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "a");              // 0
        toolRound(m, "t1", "r");   // 1,2
        user(m, "b");              // 3
        toolRound(m, "t2", "r");   // 4,5
        toolRound(m, "t3", "r");   // 6,7
        assertEquals(3, CutPlanner.cutIndex(m, 1));
        assertEquals(0, CutPlanner.cutIndex(m, 2));
        for (int k = 1; k <= 2; k++) {
            assertTrue(CutPlanner.isPlainUserText(m.get(CutPlanner.cutIndex(m, k))));
        }
    }

    @Test
    void toolResultMessageIsNotAPlainUserTurn() {
        ArrayNode m = MAPPER.createArrayNode();
        toolRound(m, "t1", "r");
        assertFalse(CutPlanner.isPlainUserText(m.get(0)));
        assertFalse(CutPlanner.isPlainUserText(m.get(1)));
        assertEquals(0, CutPlanner.cutIndex(m, 1));
    }

    @Test
    void plainUserTextShapes() {
        ArrayNode m = MAPPER.createArrayNode();
        m.addObject().put("role", "user").put("content", "text form");
        m.addObject().put("role", "user").putArray("content");
        m.addObject().put("role", "assistant").put("content", "x");
        assertTrue(CutPlanner.isPlainUserText(m.get(0)));
        assertFalse(CutPlanner.isPlainUserText(m.get(1)), "empty content");
        assertFalse(CutPlanner.isPlainUserText(m.get(2)), "assistant");
    }

    @Test
    void tokenCutIndexKeepsNewestWholeTurnsThatFit() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "a".repeat(350));  // 0
        user(m, "b".repeat(350));  // 1
        user(m, "c".repeat(350));  // 2
        long one = TokenEstimate.tokens(m.get(2).toString().length());
        assertEquals(2, CutPlanner.tokenCutIndex(m, (int) one));
        assertEquals(1, CutPlanner.tokenCutIndex(m, (int) (one * 2)));
        assertEquals(0, CutPlanner.tokenCutIndex(m, 1_000_000), "everything fits");
        assertEquals(2, CutPlanner.tokenCutIndex(m, 1), "the latest turn is kept even when larger");
    }

    @Test
    void onlySummary() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, SummaryBuilder.SUMMARY_PREFIX + "s");
        user(m, "next");
        assertTrue(CutPlanner.onlySummary(m, 1));
        assertFalse(CutPlanner.onlySummary(m, 2));
        assertFalse(CutPlanner.onlySummary(m, 0));
        ArrayNode plain = MAPPER.createArrayNode();
        user(plain, "x");
        user(plain, "y");
        assertFalse(CutPlanner.onlySummary(plain, 1));
    }

    @Test
    void inTurnCutNeedsATurnInProgress() {
        ArrayNode m = MAPPER.createArrayNode();
        assertNull(CutPlanner.inTurnCut(m, 10_000, 100, 10, 0));
        user(m, "prompt");
        assertNull(CutPlanner.inTurnCut(m, 10_000, 100, 10, 0), "nothing after the prompt");
        toolRound(m, "t1", "r");
        assertNull(CutPlanner.inTurnCut(m, 10_000, 100, 10, 0), "a single tool round");
    }

    @Test
    void inTurnCutLandsOnAnAssistantMessageAfterAToolResultAndKeepsPairs() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "prompt");               // 0
        for (int i = 0; i < 5; i++) toolRound(m, "t" + i, "r".repeat(700)); // 1..10
        long round = TokenEstimate.tokens(m.get(9).toString().length() + m.get(10).toString().length());
        var plan = CutPlanner.inTurnCut(m, 1_000_000, (int) (round * 2), 0, 0);
        assertNotNull(plan);
        assertEquals(0, plan.prompt());
        assertEquals("assistant", m.get(plan.cut()).path("role").asText());
        assertEquals("user", m.get(plan.cut() - 1).path("role").asText());
        assertEquals(7, plan.cut(), "the last two rounds fit the keep budget");
    }

    @Test
    void inTurnCutFallsBackToLatestRoundWhenNothingFits() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "prompt");
        for (int i = 0; i < 4; i++) toolRound(m, "t" + i, "r".repeat(700)); // assistants at 1,3,5,7
        var plan = CutPlanner.inTurnCut(m, 1_000_000, 1, 0, 0);
        assertNotNull(plan);
        assertEquals(7, plan.cut());
    }

    @Test
    void inTurnCutRespectsMinFreed() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "prompt");
        for (int i = 0; i < 3; i++) toolRound(m, "t" + i, "r");
        assertNotNull(CutPlanner.inTurnCut(m, 1_000_000, 1, 0, 0));
        assertNull(CutPlanner.inTurnCut(m, 1_000_000, 1, 0, 1_000_000));
    }

    @Test
    void inTurnCutWorksOnTheTurnInProgressOnly() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "old");
        toolRound(m, "o1", "r");
        toolRound(m, "o2", "r");
        user(m, "now");              // 5
        toolRound(m, "t1", "r");     // 6,7
        toolRound(m, "t2", "r");     // 8,9
        var plan = CutPlanner.inTurnCut(m, 1_000_000, 1, 0, 0);
        assertNotNull(plan);
        assertEquals(5, plan.prompt());
        assertEquals(8, plan.cut());
    }

    @Test
    void stripThinkingKeepsCurrentTurnUnlessAsked() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "old");
        thinking(m, true);
        user(m, "now");
        thinking(m, true);
        assertTrue(CutPlanner.currentTurnHasThinking(m));
        CutPlanner.stripThinking(m, false);
        assertEquals(1, m.get(1).path("content").size(), "finished turn loses its thinking");
        assertEquals(2, m.get(3).path("content").size(), "turn in progress keeps it");
        assertTrue(CutPlanner.currentTurnHasThinking(m));
        CutPlanner.stripThinking(m, true);
        assertFalse(CutPlanner.currentTurnHasThinking(m));
        assertEquals(1, m.get(3).path("content").size());
    }

    @Test
    void stripThinkingLeavesPlaceholderAndHandlesRedacted() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "p");
        thinking(m, false);
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "redacted_thinking");
        CutPlanner.stripThinking(m, true);
        assertEquals(CutPlanner.REASONING_OMITTED, m.get(1).path("content").get(0).path("text").asText());
        assertEquals(CutPlanner.REASONING_OMITTED, m.get(2).path("content").get(0).path("text").asText());
    }

    @Test
    void thinkingBeforeCurrentTurnIsNotCurrent() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "old");
        thinking(m, true);
        user(m, "now");
        toolRound(m, "t", "r");
        assertFalse(CutPlanner.currentTurnHasThinking(m));
    }
}

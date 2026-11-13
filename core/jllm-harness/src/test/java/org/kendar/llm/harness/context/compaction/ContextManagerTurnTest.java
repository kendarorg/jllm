package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.TokenEstimate;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.ApprovalService;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.config;

/** A lone earlier summary is left alone; one long turn is cut inside; the estimate follows the measured usage. */
class ContextManagerTurnTest {

    private static final String SUMMARY = "[Summary of the earlier conversation]\nPRIOR-SUMMARY";

    private final ScriptedModel model = new ScriptedModel();

    private ContextManager manager(HarnessConfig cfg) {
        return new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
    }

    private static HarnessConfig small() {
        return config(Path.of(".")).withKeepRecentTurns(2).withContextBudgetTokens(1200); // ceiling 1020
    }

    private static void user(ArrayNode m, String text) {
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void rounds(ArrayNode m, int n, int resultChars) {
        for (int i = 0; i < n; i++) {
            m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "tool_use")
                    .put("id", "t" + i).put("name", "grep").putObject("input");
            m.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                    .put("tool_use_id", "t" + i).put("content", "r".repeat(resultChars));
        }
    }

    @Test
    void aLoneEarlierSummaryIsNotSummarizedAgain() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, SUMMARY);
        user(m, "the request");
        rounds(m, 6, 800); // over the ceiling, but too small for a cut inside the turn to pay off
        String before = m.toString();
        Events ev = new Events();

        boolean changed = manager(small()).compactIfNeeded("c", m, null, ev, () -> false);

        assertFalse(changed);
        assertEquals(before, m.toString());
        assertTrue(model.simpleUserPrompts.isEmpty(), "the summary alone was sent for summarization");
        assertTrue(ev.ofType("approval_request").isEmpty(), "a rewrite of the summary alone was proposed");
    }

    @Test
    void overflowWithALoneSummaryCutsInsideTheTurnInstead() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, SUMMARY);
        user(m, "the request");
        rounds(m, 5, 800);
        String last = m.get(m.size() - 1).toString();
        model.simpleReplies.add("TURN");

        CompactResult r = manager(small()).compactOverflow(m, 10_000, u -> { });

        assertTrue(r.changed());
        assertEquals(1, model.simpleUserPrompts.size());
        assertFalse(model.simpleUserPrompts.get(0).contains("PRIOR-SUMMARY"), "the earlier summary was re-summarized");
        assertEquals(SUMMARY, m.get(0).get("content").get(0).get("text").asText());
        assertEquals("the request", m.get(1).get("content").get(0).get("text").asText());
        assertEquals(last, m.get(m.size() - 1).toString());
    }

    @Test
    void oneLongTurnIsCutInsideBeforeTheProviderRefusesIt() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "the request");
        rounds(m, 30, 600);
        String last = m.get(m.size() - 1).toString();
        model.simpleReplies.add("TURN-SUMMARY");
        Events ev = new Events();

        boolean changed = manager(small()).compactIfNeeded("c", m, null, ev, () -> false);

        assertTrue(changed);
        assertEquals(1, model.simpleUserPrompts.size());
        assertEquals("the request", m.get(0).get("content").get(0).get("text").asText());
        assertEquals(ContextManager.TURN_SUMMARY_PREFIX + "TURN-SUMMARY",
                m.get(1).get("content").get(0).get("text").asText());
        assertEquals(last, m.get(m.size() - 1).toString(), "the newest round stays intact");
        assertEquals(3, m.size());
        assertTrue(ev.ofType("compaction").stream().anyMatch(n -> n.path("inTurn").asBoolean()));
        assertTrue(ev.ofType("approval_request").isEmpty());
    }

    @Test
    void aRequestMeasuredOverTheCeilingIsCompactedEvenWhenTheEstimateFits() {
        HarnessConfig cfg = config(Path.of(".")).withContextBudgetTokens(40_000); // ceiling 24k
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "the request");
        rounds(m, 30, 2000); // ~19k tokens by the estimate
        ContextManager cm = manager(cfg);
        assertTrue(cm.estimateTokens(m, null) < 24_000);
        int before = m.size();
        model.simpleReplies.add("TURN");

        boolean changed = cm.compactIfNeeded("c", m, new Usage(30_000, 1, 0, 0), new Events(), () -> false);

        assertTrue(changed);
        assertTrue(m.size() < before);
        assertEquals("the request", m.get(0).get("content").get(0).get("text").asText());
    }

    @Test
    void theStrategyLearnsTheRatioFromTheMeasuredUsage() {
        HarnessConfig cfg = config(Path.of(".")).withContextBudgetTokens(40_000); // ceiling 24k
        CompactionStrategy s = new CompactionStrategy(manager(cfg), cfg, MAPPER);
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "the request");
        rounds(m, 20, 2000);
        long plain = s.contextTokens("c", "a", m);

        assertFalse(s.prepare(turn(m, null)).changed());
        // the provider counted 1.5 times the estimate of the request just sent
        assertFalse(s.prepare(turn(m, new Usage(plain * 3 / 2, 1, 0, 0))).changed());

        assertEquals(plain * 1.5, s.contextTokens("c", "a", m), plain * 0.01);
        s.forget("c");
        assertEquals(plain, s.contextTokens("c", "a", m));
    }

    @Test
    void theEstimateIsCharsOverThreeAndAHalf() {
        assertEquals(0, TokenEstimate.tokens(0));
        assertEquals(1, TokenEstimate.tokens(1));
        assertEquals(2, TokenEstimate.tokens(7));
        assertEquals(3, TokenEstimate.tokens(8));
    }

    private static ContextTurn turn(ArrayNode log, Usage last) {
        return new ContextTurn("c", "a", log, null, last, null, null, null, null, true);
    }
}

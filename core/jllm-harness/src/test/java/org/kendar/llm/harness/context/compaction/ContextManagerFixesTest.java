package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.ContextView;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.permission.ApprovalService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.config;

/** Overflow cut inside a turn, inherited boundary, pre-clear summaries, summary retries, fixed part, thinking. */
class ContextManagerFixesTest {

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

    private static void assistant(ArrayNode m, String text) {
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void toolRound(ArrayNode m, String id, String result) {
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "tool_use").put("id", id)
                .put("name", "grep").putObject("input");
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", id).put("content", result);
    }

    private static void assertToolPairsIntact(ArrayNode m) {
        for (int i = 0; i < m.size(); i++) {
            JsonNode c = m.get(i).get("content");
            if (!c.isArray()) continue;
            for (JsonNode b : c) {
                if ("tool_result".equals(b.path("type").asText())) {
                    assertTrue(i > 0 && m.get(i - 1).toString().contains("\"id\":\"" + b.get("tool_use_id").asText() + "\""),
                            "orphan tool_result at " + i);
                }
            }
        }
    }

    private static boolean hasThinking(ArrayNode m) {
        for (JsonNode msg : m) {
            for (JsonNode b : msg.path("content")) {
                if ("thinking".equals(b.path("type").asText())) return true;
            }
        }
        return false;
    }

    // ---- 1. overflow: cut at a tool-call boundary inside the current turn ------------------------------------------

    @Test
    void overflowInASingleLongTurnSummarizesItsOlderRoundsAndKeepsThePrompt() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "THE-TASK");
        for (int i = 1; i <= 5; i++) toolRound(m, "t" + i, "R" + i + "-" + "x".repeat(800));
        String lastRound = m.get(m.size() - 2).toString() + m.get(m.size() - 1);
        model.simpleReplies.add("TURN-SUMMARY");

        CompactResult r = manager(small()).compactOverflow(m, 900, u -> { });

        assertTrue(r.changed());
        assertEquals(1, model.simpleUserPrompts.size());
        String prompt = model.simpleUserPrompts.get(0);
        assertTrue(prompt.contains("THE-TASK") && prompt.contains("R1-") && !prompt.contains("R5-"), prompt);
        assertEquals("THE-TASK", m.get(0).path("content").path(0).path("text").asText(), "the prompt stays verbatim");
        JsonNode first = m.get(1);
        assertEquals("assistant", first.path("role").asText());
        assertTrue(first.path("content").path(0).path("text").asText()
                .startsWith(ContextManager.TURN_SUMMARY_PREFIX + "TURN-SUMMARY"));
        assertEquals("tool_use", first.path("content").path(1).path("type").asText());
        assertEquals(3, m.size(), "prompt, newest round (with the summary in front)");
        assertTrue(lastRound.contains(m.get(2).toString()));
        assertToolPairsIntact(m);
    }

    @Test
    void overflowKeepsAsManyNewestRoundsAsFit() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "THE-TASK");
        for (int i = 1; i <= 6; i++) toolRound(m, "t" + i, "R" + i + "-" + "x".repeat(800));
        model.simpleReplies.add("S");

        // room for the summary reserve and about two rounds
        manager(small()).compactOverflow(m, 3000 + 600, u -> { });

        assertToolPairsIntact(m);
        assertTrue(m.size() > 3 && m.size() < 13, "some rounds kept, some summarized: " + m.size());
        assertTrue(m.toString().contains("R6-"));
        assertFalse(m.toString().contains("R1-"));
    }

    @Test
    void overflowWithASingleRoundHasNothingToCut() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "THE-TASK");
        toolRound(m, "t1", "x".repeat(800));
        String before = m.toString();

        assertFalse(manager(small()).compactOverflow(m, 10, u -> { }).changed());
        assertEquals(before, m.toString());
        assertTrue(model.simpleUserPrompts.isEmpty());
    }

    @Test
    void strategyOverflowUsesTheTurnCutForTheMainAgent() {
        HarnessConfig cfg = small();
        CompactionStrategy s = new CompactionStrategy(manager(cfg), cfg, MAPPER);
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "THE-TASK");
        for (int i = 1; i <= 5; i++) toolRound(m, "t" + i, "x".repeat(800));
        model.simpleReplies.add("S");

        ContextView v = s.onOverflow(new ContextTurn("c", "a", m, null, null, null, new Events(), null, null, true));

        assertNotNull(v);
        assertEquals("THE-TASK", m.get(0).path("content").path(0).path("text").asText());
        assertToolPairsIntact(m);
    }

    // ---- 2. sub-agent overflow / compactNow keep the inherited boundary -------------------------------------------

    private static ArrayNode inheritedLog(int copyChars, String task, int rounds) {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "PARENT-Q " + "p".repeat(copyChars));
        assistant(m, "Delegating the next request to a sub-agent.");
        user(m, task);
        for (int i = 1; i <= rounds; i++) toolRound(m, "s" + i, "S" + i + "-" + "y".repeat(800));
        return m;
    }

    @Test
    void subAgentOverflowCompactsItsOwnPartAndKeepsTheBoundary() {
        HarnessConfig cfg = small();
        CompactionStrategy s = new CompactionStrategy(manager(cfg), cfg, MAPPER);
        ArrayNode m = inheritedLog(100, "SUB-TASK", 5);
        int[] inherited = {2};
        model.simpleHandler = u -> "OWN-SUMMARY";

        ContextView v = s.onOverflow(new ContextTurn("c", "sub", m, inherited, null, null, new Events(), null, null,
                false));

        assertNotNull(v);
        assertEquals(2, inherited[0]);
        assertTrue(m.get(0).toString().contains("PARENT-Q"), "the small copy stays");
        assertEquals("SUB-TASK", m.get(inherited[0]).path("content").path(0).path("text").asText(),
                "the boundary points at the agent's own task");
        assertToolPairsIntact(m);
    }

    @Test
    void subAgentCompactNowSummarizesABigCopyAndMovesTheBoundary() {
        HarnessConfig cfg = small();
        CompactionStrategy s = new CompactionStrategy(manager(cfg), cfg, MAPPER);
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "PARENT-Q1 " + "p".repeat(3000));
        assistant(m, "a1");
        user(m, "PARENT-Q2 " + "p".repeat(3000));
        assistant(m, "Delegating the next request to a sub-agent.");
        user(m, "SUB-TASK");
        toolRound(m, "s1", "short");
        int[] inherited = {4};
        model.simpleHandler = u -> u.contains("PARENT-Q1") ? "COPY-SUMMARY" : "OWN";

        CompactResult r = s.compactNow(new ContextTurn("c", "sub", m, inherited, null, null, new Events(), null, null,
                false), null);

        assertTrue(r.changed());
        assertEquals(2, inherited[0]);
        assertTrue(m.get(0).toString().contains("COPY-SUMMARY"));
        assertEquals("SUB-TASK", m.get(2).path("content").path(0).path("text").asText());
    }

    // ---- 4. summaries see the tool results that tier 1 clears -----------------------------------------------------

    @Test
    void manualCompactSummarizesFromTheLogBeforeToolResultsAreCleared() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "q1");
        toolRound(m, "t1", "RESULT-ONE " + "z".repeat(500));
        user(m, "q2");
        toolRound(m, "t2", "r");
        model.simpleReplies.add("S");

        CompactResult r = manager(small()).compactNow(m, null, u -> { });

        assertTrue(r.changed());
        assertEquals(1, r.clearedToolResults());
        String prompt = model.simpleUserPrompts.get(0);
        assertTrue(prompt.contains("RESULT-ONE"), prompt);
        assertFalse(prompt.contains(ContextManager.CLEARED));
    }

    @Test
    void tier2SummarizesFromTheLogBeforeTier1() {
        ArrayNode m = MAPPER.createArrayNode();
        for (int i = 1; i <= 4; i++) {
            user(m, "q" + i + " " + "p".repeat(1200));
            toolRound(m, "t" + i, "RESULT-" + i + " " + "z".repeat(500));
        }
        model.simpleReplies.add("S");

        assertTrue(manager(small()).compactIfNeeded("c", m, null, new Events(), () -> false));

        String prompt = model.simpleUserPrompts.get(0);
        assertTrue(prompt.contains("RESULT-1"), prompt);
        assertFalse(prompt.contains(ContextManager.CLEARED));
    }

    // ---- 5. a refused summary request keeps the previous summary --------------------------------------------------

    private static ArrayNode withPriorSummary(String prior, int turns) {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "[Summary of the earlier conversation]\n" + prior);
        assistant(m, "ok");
        for (int i = 1; i <= turns; i++) {
            user(m, "q" + i + " " + "p".repeat(300));
            assistant(m, "a" + i);
        }
        return m;
    }

    @Test
    void retryKeepsThePreviousSummaryWholeAndCutsTheOldestNewerHistory() {
        ArrayNode m = withPriorSummary("PRIOR-START " + "s".repeat(500) + " PRIOR-END", 100);
        List<String> prompts = new ArrayList<>();
        model.simpleHandler = u -> {
            prompts.add(u);
            if (prompts.size() == 1) throw new ModelException("prompt is too long");
            return "NEW";
        };

        CompactResult r = manager(small()).compactNow(m, null, u -> { });

        assertTrue(r.changed());
        assertEquals(2, prompts.size());
        String retry = prompts.get(1);
        assertTrue(retry.contains("PRIOR-START") && retry.contains("PRIOR-END"), "the previous summary is kept whole");
        assertTrue(retry.contains("[earlier history omitted]"));
        assertTrue(retry.indexOf("PRIOR-END") < retry.indexOf("[earlier history omitted]"));
        assertFalse(retry.contains("q1 "), "the oldest newer history goes first");
        assertTrue(retry.contains("q99 "));
        String afterMarker = retry.substring(retry.indexOf("[earlier history omitted]\n") + 26);
        assertTrue(afterMarker.startsWith("USER: ") || afterMarker.startsWith("ASSISTANT: "),
                "cut on a line boundary: " + afterMarker.substring(0, 20));
    }

    @Test
    void whenThePreviousSummaryAloneIsRefusedItIsKeptUnchanged() {
        ArrayNode m = withPriorSummary("PRIOR " + "s".repeat(20_000), 3);
        String before = m.toString();
        List<String> prompts = new ArrayList<>();
        model.simpleHandler = u -> {
            prompts.add(u);
            throw new ModelException("prompt is too long");
        };

        CompactResult r = manager(small()).compactNow(m, null, u -> { });

        assertFalse(r.changed());
        assertEquals(before, m.toString());
        assertTrue(prompts.size() < 5, "stops once the previous summary alone does not fit: " + prompts.size());
        for (String p : prompts) assertTrue(p.contains("s".repeat(20_000)), "never sent a cut previous summary");
    }

    // ---- 9. the fixed part of the request counts in every check ---------------------------------------------------

    @Test
    void theFixedPartOfTheRequestTriggersAndDrivesTheTiers() {
        ArrayNode m = MAPPER.createArrayNode();
        for (int i = 1; i <= 4; i++) {
            user(m, "q" + i);
            toolRound(m, "t" + i, "z".repeat(500));
        }
        // messages alone (~700 tokens) are under the 1020 ceiling; with 900 tokens of system text they are not, and
        // clearing the old results (tier 1) is not enough
        RequestPrefix prefix = new RequestPrefix("P".repeat(3600), "", List.of(), new ScriptedModel(), false);
        model.simpleReplies.add("S");
        Events ev = new Events();

        assertTrue(manager(small()).compactIfNeeded("c", m, null, ev, () -> false, u -> { }, true, prefix));

        assertTrue(ev.ofType("compaction").stream().anyMatch(n -> n.get("tier").asInt() == 1));
        assertTrue(ev.ofType("compaction").stream().anyMatch(n -> n.get("tier").asInt() == 2),
                "tier 2 ran: tier 1 only fit on paper");
    }

    @Test
    void fixedTokensCountsSystemTextAndTools() {
        assertEquals(0, ContextManager.fixedTokens(null));
        assertEquals(3, ContextManager.fixedTokens(new RequestPrefix("1234", "12345678", null, null, false)));
    }

    // ---- 10. thinking ---------------------------------------------------------------------------------------------

    @Test
    void aThinkingOnlyMessageKeepsAPlaceholder() {
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "q");
        ArrayNode c = m.addObject().put("role", "assistant").putArray("content");
        c.addObject().put("type", "thinking").put("thinking", "t").put("signature", "sig");
        c.addObject().put("type", "redacted_thinking").put("data", "d");

        ContextManager.stripThinking(m, true);

        JsonNode content = m.get(1).path("content");
        assertEquals(1, content.size());
        assertEquals(ContextManager.REASONING_OMITTED, content.path(0).path("text").asText());
    }

    @Test
    void compactInheritedDropsThinkingOnceItRewrote() {
        HarnessConfig cfg = config(Path.of(".")).withKeepRecentTurns(1).withContextBudgetTokens(400);
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "PARENT " + "p".repeat(2000));
        ArrayNode c = m.addObject().put("role", "assistant").putArray("content");
        c.addObject().put("type", "thinking").put("thinking", "t").put("signature", "sig");
        c.addObject().put("type", "text").put("text", "Delegating");
        user(m, "SUB-TASK");
        ObjectNode a = m.addObject().put("role", "assistant");
        a.putArray("content").addObject().put("type", "thinking").put("thinking", "own").put("signature", "s2");
        ((ArrayNode) a.get("content")).addObject().put("type", "text").put("text", "done");
        int[] inherited = {2};
        model.simpleHandler = u -> "SUMMARY";

        assertTrue(manager(cfg).compactInherited("c", m, inherited, null, new Events(), u -> { }, null));

        assertEquals(2, inherited[0]);
        assertFalse(hasThinking(m), m.toString());
    }

    @Test
    void compactInheritedWaitsForTheTurnWhenAskedTo() {
        HarnessConfig cfg = config(Path.of(".")).withKeepRecentTurns(1).withContextBudgetTokens(400)
                .withCompaction(new HarnessConfig.Compaction(true, 15_000, null, true));
        ArrayNode m = MAPPER.createArrayNode();
        user(m, "PARENT " + "p".repeat(2000));
        assistant(m, "Delegating");
        user(m, "SUB-TASK");
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "thinking")
                .put("thinking", "t").put("signature", "s");
        String before = m.toString();

        assertFalse(manager(cfg).compactInherited("c", m, new int[]{2}, null, new Events(), u -> { }, null));
        assertEquals(before, m.toString());
    }
}

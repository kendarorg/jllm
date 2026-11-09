package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.ApprovalService;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.config;

/** Tiered compaction: 1 microcompact (no LLM) -> 2 summarize (LLM) -> 3 proposal that waits for the user. */
class ContextManagerCompactionTest {

    private HarnessConfig config;
    private ScriptedModel model;
    private ApprovalService approvals;
    private ContextManager manager;

    @BeforeEach
    void setUp() {
        config = config(Path.of(".")).withKeepRecentTurns(2).withContextBudgetTokens(1200);
        model = new ScriptedModel();
        approvals = new ApprovalService(config.approvalTimeoutSeconds());
        manager = new ContextManager(config, model, approvals, MAPPER);
    }

    private static void user(ArrayNode m, String text) {
        var msg = m.addObject();
        msg.put("role", "user");
        msg.putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void toolRound(ArrayNode m, String id, String result) {
        var a = m.addObject();
        a.put("role", "assistant");
        a.putArray("content").addObject().put("type", "tool_use").put("id", id).put("name", "grep").putObject("input");
        var u = m.addObject();
        u.put("role", "user");
        u.putArray("content").addObject().put("type", "tool_result").put("tool_use_id", id).put("content", result);
    }

    /** 4 turns, each with a tool round carrying {@code resultChars} chars; user prompts are {@code promptChars}. */
    private ArrayNode conversation(int promptChars, int resultChars) {
        ArrayNode m = MAPPER.createArrayNode();
        for (int i = 1; i <= 4; i++) {
            user(m, ("q" + i + " ").repeat(1).concat("p".repeat(promptChars)));
            toolRound(m, "t" + i, "r".repeat(resultChars));
        }
        return m;
    }

    @Test
    void underBudgetDoesNothing() {
        ArrayNode m = conversation(10, 10);
        String before = m.toString();

        boolean changed = manager.compactIfNeeded("c", m, new Usage(10, 1, 0, 0), new Events(), () -> false);

        assertFalse(changed);
        assertEquals(before, m.toString());
        assertTrue(model.simpleUserPrompts.isEmpty());
    }

    @Test
    void tier1MicrocompactSufficesAndMakesNoLlmCall() {
        ArrayNode m = conversation(10, 1500);
        manager = new ContextManager(config.withContextBudgetTokens(1200), model, approvals, MAPPER);
        Events ev = new Events();

        boolean changed = manager.compactIfNeeded("c", m, null, ev, () -> false);

        assertTrue(changed);
        assertTrue(model.simpleUserPrompts.isEmpty(), "tier 1 must not call the model");
        assertEquals(1, ev.ofType("compaction").size());
        assertEquals(1, ev.ofType("compaction").get(0).get("tier").asInt());
        assertEquals(ContextManager.CLEARED, m.get(2).get("content").get(0).get("content").asText());
        // recent turns keep their results
        assertEquals(1500, m.get(m.size() - 1).get("content").get(0).get("content").asText().length());
        assertEquals(12, m.size(), "no message was removed");
    }

    @Test
    void tier2SummarizesOlderTurnsKeepingRecentOnesVerbatim() {
        ArrayNode m = conversation(1500, 10); // big prompts: tier 1 has nothing worth clearing
        model.simpleReplies.add("SUMMARY-TEXT");
        String lastTurn = m.get(m.size() - 1).toString();
        Events ev = new Events();

        boolean changed = manager.compactIfNeeded("c", m, null, ev, () -> false);

        assertTrue(changed);
        assertEquals(1, model.simpleUserPrompts.size());
        assertTrue(model.simpleUserPrompts.get(0).contains("q1 "), "older turns were sent for summarization");
        assertFalse(model.simpleUserPrompts.get(0).contains("q4 "), "recent turns are kept, not summarized");
        assertTrue(ev.ofType("compaction").stream().anyMatch(n -> n.get("tier").asInt() == 2));
        assertTrue(ev.ofType("approval_request").isEmpty());
        JsonNode first = m.get(0);
        assertEquals("user", first.get("role").asText());
        assertTrue(first.get("content").get(0).get("text").asText().endsWith("SUMMARY-TEXT"));
        assertEquals(lastTurn, m.get(m.size() - 1).toString());
        assertToolPairsIntact(m);
    }

    @Test
    void manualCompactSummarizesEvenUnderBudgetAndKeepsLastTurn() {
        ArrayNode m = conversation(10, 10); // far under budget
        model.simpleReplies.add("MANUAL-SUMMARY");
        String lastTurn = m.get(m.size() - 1).toString();

        CompactResult r = manager.compactNow(m, "open ticket keys", null);

        assertTrue(r.changed());
        assertEquals(1, model.simpleUserPrompts.size());
        assertTrue(model.simpleUserPrompts.get(0).contains("q1 "));
        assertFalse(model.simpleUserPrompts.get(0).contains("q4 "));
        assertTrue(m.get(0).toString().contains("MANUAL-SUMMARY"));
        assertEquals(lastTurn, m.get(m.size() - 1).toString());
    }

    @Test
    void tier2FailureWarnsAndKeepsMessages() {
        ArrayNode m = conversation(1500, 10);
        Events ev = new Events();
        // no scripted summary: summarize throws; tier 3 also fails -> warnings, no crash, nothing lost
        String before = m.toString();

        manager.compactIfNeeded("c", m, null, ev, () -> false);

        assertEquals(before, m.toString());
        assertFalse(ev.ofType("warning").isEmpty());
    }

    /** Runs compaction on a thread so the test can act as the user while tier 3 blocks. */
    private boolean compactWithUser(ArrayNode m, Events ev, boolean approve, String edited) {
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) {
                assertTrue(approvals.resolve("c", data.get("id").asText(), approve, edited));
            }
        };
        return manager.compactIfNeeded("c", m, null, ev, () -> false);
    }

    @Test
    void tier3ProposesRewriteAndAppliesItOnApproval() {
        ArrayNode m = conversation(1500, 10);
        model.simpleReplies.add("x".repeat(4000)); // tier 2 summary is still far over budget
        model.simpleReplies.add("PROPOSED-REWRITE");
        Events ev = new Events();

        boolean changed = compactWithUser(m, ev, true, null);

        assertTrue(changed);
        assertEquals(1, ev.ofType("approval_request").size());
        JsonNode req = ev.ofType("approval_request").get(0);
        assertEquals("context", req.get("type").asText());
        assertEquals("PROPOSED-REWRITE", req.get("proposed").asText());
        assertTrue(req.get("preview").asText().startsWith("USER: "), "old transcript is shown for the diff view");
        assertTrue(m.get(0).get("content").get(0).get("text").asText().endsWith("PROPOSED-REWRITE"));
        assertTrue(ev.ofType("compaction").stream().anyMatch(n -> n.get("tier").asInt() == 3));
        assertEquals(4, m.size(), "summary + the latest turn (user, tool_use, tool_result)");
        assertToolPairsIntact(m);
    }

    @Test
    void tier3UserEditedTextWinsOverProposal() {
        ArrayNode m = conversation(1500, 10);
        model.simpleReplies.add("x".repeat(4000));
        model.simpleReplies.add("PROPOSED");
        Events ev = new Events();

        compactWithUser(m, ev, true, "MY EDIT");

        assertTrue(m.get(0).get("content").get(0).get("text").asText().endsWith("MY EDIT"));
    }

    @Test
    void tier3DeclineKeepsMessagesAndIsNotRepeatedUntilContextGrows() {
        ArrayNode m = conversation(1500, 10);
        model.simpleReplies.add("x".repeat(4000));
        model.simpleReplies.add("PROPOSED");
        Events ev = new Events();

        compactWithUser(m, ev, false, null);

        assertTrue(ev.ofType("compaction").stream().noneMatch(n -> n.get("tier").asInt() == 3));
        String afterDecline = m.toString();
        assertTrue(afterDecline.contains("x".repeat(4000)), "tier-2 result remains, nothing rewritten");

        // same size again: no second prompt, no second LLM call
        Events ev2 = new Events();
        manager.compactIfNeeded("c", m, null, ev2, () -> false);
        assertTrue(ev2.ofType("approval_request").isEmpty());
        assertEquals(afterDecline, m.toString());
    }

    @Test
    void tier3CancelledWhileWaitingCountsAsDecline() {
        ArrayNode m = conversation(1500, 10);
        model.simpleReplies.add("x".repeat(4000));
        model.simpleReplies.add("PROPOSED");
        AtomicReference<Boolean> stop = new AtomicReference<>(false);
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) stop.set(true);
        };

        manager.compactIfNeeded("c", m, null, ev, stop::get);

        assertTrue(ev.ofType("compaction").stream().noneMatch(n -> n.get("tier").asInt() == 3));
        assertTrue(approvals.pending("c").isEmpty());
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
}

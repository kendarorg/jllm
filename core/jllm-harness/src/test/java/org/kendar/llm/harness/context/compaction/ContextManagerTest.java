package org.kendar.llm.harness.context.compaction;

import org.kendar.llm.harness.context.ClearedResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.permission.ApprovalService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ContextManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContextManager manager = new ContextManager(
            HarnessConfig.defaults(), mock(ModelClient.class), mock(ApprovalService.class), MAPPER);

    private void userText(ArrayNode m, String text) {
        var msg = m.addObject();
        msg.put("role", "user");
        msg.putArray("content").addObject().put("type", "text").put("text", text);
    }

    private void toolRound(ArrayNode m, String id, String result) {
        var a = m.addObject();
        a.put("role", "assistant");
        a.putArray("content").addObject().put("type", "tool_use").put("id", id).put("name", "grep");
        var u = m.addObject();
        u.put("role", "user");
        u.putArray("content").addObject().put("type", "tool_result").put("tool_use_id", id).put("content", result);
    }

    @Test
    void microcompactBlanksOnlyOldToolResults() {
        String big = "x".repeat(500);
        ArrayNode m = MAPPER.createArrayNode();
        userText(m, "first");
        toolRound(m, "t1", big);
        userText(m, "second");
        toolRound(m, "t2", big);

        int cleared = manager.microcompact(m, 1);

        assertEquals(1, cleared);
        assertEquals(ClearedResult.TEXT, m.get(2).get("content").get(0).get("content").asText());
        assertEquals(big, m.get(5).get("content").get(0).get("content").asText());
    }

    private void thinkingRound(ArrayNode m, String id, String result) {
        var a = m.addObject();
        a.put("role", "assistant");
        var c = a.putArray("content");
        c.addObject().put("type", "thinking").put("thinking", "t").put("signature", "sig");
        c.addObject().put("type", "tool_use").put("id", id).put("name", "grep");
        var u = m.addObject();
        u.put("role", "user");
        u.putArray("content").addObject().put("type", "tool_result").put("tool_use_id", id).put("content", result);
    }

    @Test
    void microcompactDropsThinkingOfFinishedTurnsOnly() {
        String big = "x".repeat(500);
        ArrayNode m = MAPPER.createArrayNode();
        userText(m, "first");
        thinkingRound(m, "t1", big);
        userText(m, "second");
        thinkingRound(m, "t2", big);

        manager.microcompact(m, 1);

        assertEquals("tool_use", m.get(1).get("content").get(0).get("type").asText());
        assertEquals("thinking", m.get(4).get("content").get(0).get("type").asText());
        assertTrue(CutPlanner.currentTurnHasThinking(m));
        CutPlanner.stripThinking(m, true);
        assertEquals("tool_use", m.get(4).get("content").get(0).get("type").asText());
        assertEquals(false, CutPlanner.currentTurnHasThinking(m));
    }

    @Test
    void cutIndexNeverSplitsToolPairs() {
        ArrayNode m = MAPPER.createArrayNode();
        userText(m, "first");
        toolRound(m, "t1", "r");
        userText(m, "second");
        toolRound(m, "t2", "r");

        int cut = CutPlanner.cutIndex(m, 1);

        assertEquals(3, cut);
        assertTrue(CutPlanner.isPlainUserText(m.get(cut)));
    }
}

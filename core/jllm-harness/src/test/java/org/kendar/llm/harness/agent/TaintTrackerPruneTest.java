package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.context.ClearedResult;
import org.kendar.llm.harness.session.ChatSessionService;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaintTrackerPruneTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static ArrayNode log(String content) {
        ArrayNode a = M.createArrayNode();
        var m = a.addObject();
        m.put("role", "user");
        m.putArray("content").addObject().put("type", "tool_result").put("tool_use_id", "t1")
                .put("content", content);
        return a;
    }

    private static TaintTracker tracker() {
        TaintTracker t = new TaintTracker(Mockito.mock(ChatSessionService.class));
        t.taint("c", "t1");
        return t;
    }

    @Test
    void clearedResultKeepsTaintUntilTheNextUserMessage() {
        TaintTracker t = tracker();
        t.prune("c", log(ClearedResult.TEXT), false);
        assertTrue(t.isTainted("c"));
        t.prune("c", log(ClearedResult.TEXT), true);
        assertFalse(t.isTainted("c"));
    }

    @Test
    void resultGoneFromTheLogDropsTaint() {
        TaintTracker t = tracker();
        t.prune("c", M.createArrayNode(), false);
        assertFalse(t.isTainted("c"));
    }

    @Test
    void resultStillInContextKeepsTaintAcrossUserMessages() {
        TaintTracker t = tracker();
        t.prune("c", log("page text"), true);
        assertTrue(t.isTainted("c"));
    }
}

package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPairingRepairTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ArrayNode log(String json) throws Exception {
        return (ArrayNode) MAPPER.readTree(json.replace('\'', '"'));
    }

    @Test
    void completeLogIsLeftAlone() throws Exception {
        ArrayNode m = log("[{'role':'user','content':'hi'},"
                + "{'role':'assistant','content':[{'type':'tool_use','id':'a','name':'x','input':{}}]},"
                + "{'role':'user','content':[{'type':'tool_result','tool_use_id':'a','content':'ok'}]}]");
        String before = m.toString();

        assertFalse(ToolPairingRepair.repair(m, MAPPER));

        assertEquals(before, m.toString());
    }

    @Test
    void callWithoutAnyFollowingMessageIsLeftAlone() throws Exception {
        ArrayNode m = log("[{'role':'user','content':'hi'},"
                + "{'role':'assistant','content':[{'type':'tool_use','id':'a','name':'x','input':{}}]}]");

        assertFalse(ToolPairingRepair.repair(m, MAPPER), "the last message may still be running");

        assertEquals(2, m.size());
    }

    @Test
    void unansweredCallGetsAnErrorResultInANewUserMessage() throws Exception {
        ArrayNode m = log("[{'role':'user','content':'hi'},"
                + "{'role':'assistant','content':[{'type':'tool_use','id':'a','name':'x','input':{}}]},"
                + "{'role':'assistant','content':[{'type':'text','text':'later'}]}]");

        assertTrue(ToolPairingRepair.repair(m, MAPPER));

        assertEquals(4, m.size());
        JsonNode inserted = m.get(2);
        assertEquals("user", inserted.get("role").asText());
        JsonNode result = inserted.get("content").get(0);
        assertEquals("tool_result", result.get("type").asText());
        assertEquals("a", result.get("tool_use_id").asText());
        assertTrue(result.get("is_error").asBoolean());
        assertEquals("later", m.get(3).get("content").get(0).get("text").asText());
    }

    @Test
    void missingResultsGoFirstInTheFollowingUserMessage() throws Exception {
        ArrayNode m = log("[{'role':'assistant','content':["
                + "{'type':'tool_use','id':'a','name':'x','input':{}},{'type':'tool_use','id':'b','name':'x','input':{}}]},"
                + "{'role':'user','content':[{'type':'tool_result','tool_use_id':'b','content':'ok'},"
                + "{'type':'text','text':'more'}]}]");

        assertTrue(ToolPairingRepair.repair(m, MAPPER));

        ArrayNode content = (ArrayNode) m.get(1).get("content");
        assertEquals(3, content.size());
        assertEquals("a", content.get(0).get("tool_use_id").asText());
        assertEquals("b", content.get(1).get("tool_use_id").asText());
        assertEquals("text", content.get(2).get("type").asText());
        assertFalse(ToolPairingRepair.repair(m, MAPPER), "a repaired log needs no second repair");
    }

    @Test
    void textualUserContentIsKeptAfterTheAddedResults() throws Exception {
        ArrayNode m = log("[{'role':'assistant','content':[{'type':'tool_use','id':'a','name':'x','input':{}}]},"
                + "{'role':'user','content':'typed meanwhile'}]");

        assertTrue(ToolPairingRepair.repair(m, MAPPER));

        ArrayNode content = (ArrayNode) m.get(1).get("content");
        assertEquals("tool_result", content.get(0).get("type").asText());
        assertEquals("typed meanwhile", content.get(1).get("text").asText());
    }

    @Test
    void toolUseIdsCollectsEveryCallOfTheLog() throws Exception {
        ArrayNode m = log("[{'role':'assistant','content':[{'type':'tool_use','id':'a','name':'x','input':{}}]},"
                + "{'role':'user','content':[{'type':'tool_result','tool_use_id':'a','content':'ok'}]},"
                + "{'role':'assistant','content':[{'type':'tool_use','id':'b','name':'x','input':{}},"
                + "{'type':'text','text':'t'}]}]");

        assertEquals(Set.of("a", "b"), ToolPairingRepair.toolUseIds(m));
    }
}

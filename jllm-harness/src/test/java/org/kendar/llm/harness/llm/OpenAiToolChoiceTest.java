package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiToolChoiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiCompatModelClient client = new OpenAiCompatModelClient(
            new LlmSettings("t", "http://x/v1", "k", "m", 100, 0, 5, true, false), mapper, 1);

    private HarnessTool tool() {
        return new HarnessTool() {
            public String name() { return "a"; }
            public String description() { return "d"; }
            public JsonNode inputSchema() { return mapper.createObjectNode(); }
            public String group() { return "g"; }
            public ToolKind kind() { return ToolKind.READ_ONLY; }
            public String execute(ToolContext ctx, JsonNode input) { return ""; }
        };
    }

    private JsonNode body(String choice, boolean noParallel) {
        return client.buildBody(new ModelRequest("s", "", mapper.createArrayNode(), List.of(tool()), 0, choice, noParallel));
    }

    @Test
    void mapsToolChoiceAndParallelFlag() {
        JsonNode d = body(null, false);
        assertTrue(d.path("tool_choice").isMissingNode());
        assertTrue(d.path("parallel_tool_calls").isMissingNode());
        assertEquals("required", body("any", false).get("tool_choice").asText());
        assertEquals("none", body("none", false).get("tool_choice").asText());
        JsonNode s = body("a", true);
        assertEquals("function", s.at("/tool_choice/type").asText());
        assertEquals("a", s.at("/tool_choice/function/name").asText());
        assertFalse(s.get("parallel_tool_calls").asBoolean());
    }

    @Test
    void existingFiveArgConstructorStillWorks() {
        ModelRequest r = new ModelRequest("s", "", mapper.createArrayNode(), List.of(), 10);
        assertNull(r.toolChoice());
        assertFalse(r.disableParallelToolUse());
    }
}

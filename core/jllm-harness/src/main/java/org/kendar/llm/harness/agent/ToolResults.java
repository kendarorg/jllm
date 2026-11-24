package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds the {@code tool_result} blocks the loop writes itself. */
final class ToolResults {
    private ToolResults() {
    }

    static ObjectNode of(ObjectMapper mapper, String id, String content, boolean isError) {
        ObjectNode r = mapper.createObjectNode();
        r.put("type", "tool_result");
        r.put("tool_use_id", id);
        r.put("content", content.isEmpty() ? "(no output)" : content);
        if (isError) r.put("is_error", true);
        return r;
    }
}

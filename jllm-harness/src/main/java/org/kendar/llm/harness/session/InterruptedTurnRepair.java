package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A process that died mid-run can leave an assistant message with {@code tool_use} blocks and no matching
 * {@code tool_result}. Providers reject such a log, so a loaded log is repaired by appending a user message with one
 * synthetic {@code tool_result} per unanswered call. A log ending with a user message is left as is.
 */
public final class InterruptedTurnRepair {

    public static final String MARKER = "interrupted: process stopped";

    private InterruptedTurnRepair() {
    }

    /** @return the repaired JSON lines, or {@code null} when nothing had to change */
    public static String repair(String jsonLines, ObjectMapper mapper) {
        if (jsonLines == null || jsonLines.isBlank()) return null;
        try {
            List<String> lines = new ArrayList<>();
            String lastMessage = null;
            for (String l : jsonLines.split("\n")) {
                if (l.isBlank()) continue;
                lines.add(l);
                if (!ChatSessionData.isReference(l)) lastMessage = l;
            }
            if (lastMessage == null) return null;
            JsonNode last = mapper.readTree(lastMessage);
            if (!"assistant".equals(last.path("role").asText())) return null;
            ArrayNode results = mapper.createArrayNode();
            JsonNode content = last.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if (!"tool_use".equals(block.path("type").asText())) continue;
                    ObjectNode r = mapper.createObjectNode();
                    r.put("type", "tool_result");
                    r.put("tool_use_id", block.path("id").asText());
                    r.put("content", MARKER);
                    r.put("is_error", true);
                    results.add(r);
                }
            }
            if (results.isEmpty()) return null;
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", "user");
            msg.set("content", results);
            return String.join("\n", lines) + "\n" + mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return null; // a corrupt log is reported by the caller when it is parsed
        }
    }
}

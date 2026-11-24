package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the message log valid for the APIs: every tool_use is answered by a tool_result right after it. */
final class ToolPairingRepair {
    private static final Logger log = LoggerFactory.getLogger(ToolPairingRepair.class);

    private ToolPairingRepair() {
    }

    /**
     * Gives every tool_use of an assistant message that is followed by another message an (error) tool_result in the
     * message right after it, as the APIs require. The last message is left alone (its calls may still run).
     *
     * @return whether the log changed
     */
    static boolean repair(ArrayNode messages, ObjectMapper mapper) {
        boolean changed = false;
        for (int i = 0; i < messages.size() - 1; i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText())) continue;
            List<String> uses = new ArrayList<>();
            for (JsonNode b : m.path("content")) {
                if ("tool_use".equals(b.path("type").asText())) uses.add(b.path("id").asText());
            }
            if (uses.isEmpty()) continue;
            JsonNode next = messages.get(i + 1);
            Set<String> answered = new HashSet<>();
            if ("user".equals(next.path("role").asText())) {
                for (JsonNode b : next.path("content")) {
                    if ("tool_result".equals(b.path("type").asText())) answered.add(b.path("tool_use_id").asText());
                }
            }
            List<String> missing = uses.stream().filter(id -> !answered.contains(id)).toList();
            if (missing.isEmpty()) continue;
            ArrayNode fixed = mapper.createArrayNode();
            for (String id : missing) {
                fixed.add(ToolResults.of(mapper, id, "Not executed: the call was interrupted.", true));
            }
            if ("user".equals(next.path("role").asText())) {
                // tool results go first in the user message
                JsonNode old = next.path("content");
                if (old.isArray()) fixed.addAll((ArrayNode) old);
                else if (old.isTextual()) fixed.addObject().put("type", "text").put("text", old.asText());
                ((ObjectNode) next).set("content", fixed);
            } else {
                ObjectNode user = mapper.createObjectNode();
                user.put("role", "user");
                user.set("content", fixed);
                messages.insert(i + 1, user);
            }
            log.warn("Answered {} tool call(s) left without a result", missing.size());
            changed = true;
        }
        return changed;
    }

    /** The tool_use ids of a message log. */
    static Set<String> toolUseIds(ArrayNode messages) {
        Set<String> ids = new HashSet<>();
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_use".equals(b.path("type").asText())) ids.add(b.path("id").asText());
            }
        }
        return ids;
    }
}

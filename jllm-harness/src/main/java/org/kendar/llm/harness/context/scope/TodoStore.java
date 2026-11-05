package org.kendar.llm.harness.context.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.state.StateStore;

import java.util.HashSet;
import java.util.Set;

/** The todo list of an agent, kept outside the transcript so that no compaction can lose it. */
public final class TodoStore {

    public static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");

    private final StateStore state;
    private final ObjectMapper mapper;

    public TodoStore(StateStore state, ObjectMapper mapper) {
        this.state = state;
        this.mapper = mapper;
    }

    private static String name(String agentKey) {
        return "todos-" + (agentKey == null || agentKey.isBlank() ? "main" : agentKey);
    }

    /** The current list; an empty array when none was written. */
    public ArrayNode read(String chatId, String agentKey) {
        JsonNode n = state.load(chatId, name(agentKey));
        return n != null && n.isArray() ? (ArrayNode) n : mapper.createArrayNode();
    }

    /**
     * Replaces the whole list.
     *
     * @throws IllegalArgumentException when an item is malformed or more than one is {@code in_progress}
     */
    public ArrayNode replace(String chatId, String agentKey, JsonNode todos) {
        if (todos == null || !todos.isArray()) throw new IllegalArgumentException("'todos' must be an array");
        ArrayNode out = mapper.createArrayNode();
        Set<String> ids = new HashSet<>();
        int inProgress = 0;
        int i = 0;
        for (JsonNode t : todos) {
            i++;
            String content = t.path("content").asText("").strip();
            if (content.isEmpty()) throw new IllegalArgumentException("Todo " + i + " has no content");
            String status = t.path("status").asText("pending");
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("Todo " + i + ": status must be pending, in_progress or completed");
            }
            String id = t.path("id").asText("");
            if (id.isBlank()) id = String.valueOf(i);
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate todo id '" + id + "'");
            if (status.equals("in_progress")) inProgress++;
            ObjectNode o = out.addObject();
            o.put("id", id);
            o.put("content", content);
            o.put("status", status);
        }
        if (inProgress > 1) throw new IllegalArgumentException("Only one todo may be in_progress");
        state.save(chatId, name(agentKey), out);
        return out;
    }

    /** The list as plain text, one line per item; null when it is empty. */
    public static String render(ArrayNode todos) {
        if (todos == null || todos.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : todos) {
            String mark = switch (t.path("status").asText()) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append(mark).append(' ').append(t.path("id").asText()).append(". ").append(t.path("content").asText())
                    .append('\n');
        }
        return sb.toString();
    }
}

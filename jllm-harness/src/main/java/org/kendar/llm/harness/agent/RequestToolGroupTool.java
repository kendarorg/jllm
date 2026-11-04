package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lets the agent enable more tool groups for the current chat. The tool set of a chat is fixed on its first message
 * so the prompt prefix (and the model's KV cache) stays stable; this is the only way it grows, taking effect on the
 * next model call. Like {@link SubAgentTool}, the agent loop adds it to the main agent's tools only.
 */
public class RequestToolGroupTool implements HarnessTool {

    private final HarnessToolRegistry registry;
    private final ChatSessionService sessions;
    private final ObjectMapper mapper;

    public RequestToolGroupTool(HarnessToolRegistry registry, ChatSessionService sessions, ObjectMapper mapper) {
        this.registry = registry;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override public String name() { return "request_tool_group"; }

    @Override
    public String description() {
        return "Enable additional tool groups that are not available yet. They become callable from your next "
                + "step. Use only when none of the current tools can do what is needed.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode groups = root.putObject("properties").putObject("groups");
        groups.put("type", "array");
        groups.put("description", "Names of the tool groups to enable");
        var items = groups.putObject("items");
        items.put("type", "string");
        var values = items.putArray("enum");
        allGroups().forEach(values::add);
        root.putArray("required").add("groups");
        return root;
    }

    @Override public String group() { return "Agents"; }

    @Override public ToolKind kind() { return ToolKind.READ_ONLY; }

    /** Groups the chat does not have yet; empty when nothing is left to request. */
    public Set<String> missing(String chatId) {
        Set<String> enabled = sessions.toolGroups(chatId);
        if (enabled == null) return Set.of();
        Set<String> out = allGroups();
        out.removeAll(enabled);
        return out;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) {
        List<String> requested = new ArrayList<>();
        for (JsonNode g : input.path("groups")) {
            if (g.isTextual()) requested.add(g.asText());
        }
        if (requested.isEmpty()) throw new IllegalArgumentException("'groups' must be a non-empty array of strings");
        Set<String> known = allGroups();
        List<String> unknown = requested.stream().filter(g -> !known.contains(g)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown tool group(s) " + unknown + "; available: " + known);
        }
        sessions.addToolGroups(ctx.chatId(), requested);
        return "Enabled " + requested + ". Their tools are available from your next step.";
    }

    private Set<String> allGroups() {
        Set<String> out = new TreeSet<>();
        for (HarnessTool t : registry.all()) {
            if (t.group() != null) out.add(t.group());
        }
        return out;
    }
}

package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The tools, MCP servers, REST APIs and skills of one chat, toggled by {@code /tools}, {@code /mcp}, {@code /apis} and
 * {@code /skills} without touching the global state (used for web callers). {@code base} is what was enabled when the
 * chat started: an item can be switched off and back on for the chat, never switched on beyond that set.
 * <p>
 * Tools of an MCP server are named {@code mcp__<server>__<tool>}, those of a REST API {@code <api>__<operation>}
 * (names sanitized to {@code [a-zA-Z0-9_-]}): a switched-off server or API hides every tool with that prefix.
 */
public final class ChatToolSet {

    public enum Kind { TOOL, MCP, API, SKILL }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Kind, Set<String>> base = new EnumMap<>(Kind.class);
    private final Map<Kind, Set<String>> off = new EnumMap<>(Kind.class);

    private ChatToolSet() {
        for (Kind k : Kind.values()) {
            base.put(k, new TreeSet<>());
            off.put(k, new TreeSet<>());
        }
    }

    /** The set captured when the chat starts: everything given is on. */
    public static ChatToolSet capture(Collection<String> tools, Collection<String> mcp, Collection<String> apis,
                                      Collection<String> skills) {
        ChatToolSet s = new ChatToolSet();
        if (tools != null) s.base.get(Kind.TOOL).addAll(tools);
        if (mcp != null) s.base.get(Kind.MCP).addAll(mcp);
        if (apis != null) s.base.get(Kind.API).addAll(apis);
        if (skills != null) s.base.get(Kind.SKILL).addAll(skills);
        return s;
    }

    /** @return null for a null or blank text; an unreadable text gives an empty set (nothing offered) */
    public static ChatToolSet parse(String json) {
        if (json == null || json.isBlank()) return null;
        ChatToolSet s = new ChatToolSet();
        try {
            JsonNode n = MAPPER.readTree(json);
            for (Kind k : Kind.values()) {
                String key = k.name().toLowerCase();
                n.path("base").path(key).forEach(v -> s.base.get(k).add(v.asText()));
                n.path("off").path(key).forEach(v -> s.off.get(k).add(v.asText()));
            }
        } catch (Exception e) {
            // fail closed: a corrupt set offers nothing until it is captured again
        }
        return s;
    }

    public String toJson() {
        ObjectNode n = MAPPER.createObjectNode();
        ObjectNode b = n.putObject("base");
        ObjectNode o = n.putObject("off");
        for (Kind k : Kind.values()) {
            String key = k.name().toLowerCase();
            base.get(k).forEach(b.putArray(key)::add);
            off.get(k).forEach(o.putArray(key)::add);
        }
        return n.toString();
    }

    /** Items of the kind offered when the chat started. */
    public Set<String> base(Kind kind) {
        return java.util.Collections.unmodifiableSet(base.get(kind));
    }

    public boolean isOn(Kind kind, String name) {
        return base.get(kind).contains(name) && !off.get(kind).contains(name);
    }

    /**
     * Switches an item on or off for this chat.
     *
     * @throws IllegalArgumentException when the item was not enabled when the chat started
     */
    public ChatToolSet with(Kind kind, String name, boolean on) {
        if (!base.get(kind).contains(name)) {
            throw new IllegalArgumentException("'" + name + "' was not available when this chat started");
        }
        ChatToolSet s = copy();
        if (on) s.off.get(kind).remove(name);
        else s.off.get(kind).add(name);
        return s;
    }

    /** True when the tool may be offered to and called by the model in this chat. */
    public boolean allowsTool(String toolName) {
        if (toolName == null) return false;
        if (off.get(Kind.TOOL).contains(toolName)) return false;
        for (String server : off.get(Kind.MCP)) {
            if (toolName.startsWith(toolName("mcp__" + server + "__"))) return false;
        }
        for (String api : off.get(Kind.API)) {
            if (toolName.startsWith(toolName(api + "__"))) return false;
        }
        return true;
    }

    public boolean allowsSkill(String skill) {
        return skill != null && !off.get(Kind.SKILL).contains(skill);
    }

    private ChatToolSet copy() {
        ChatToolSet s = new ChatToolSet();
        for (Kind k : Kind.values()) {
            s.base.get(k).addAll(base.get(k));
            s.off.get(k).addAll(off.get(k));
        }
        return s;
    }

    /** Same sanitizing as the tool providers ({@code ToolNames}). */
    private static String toolName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}

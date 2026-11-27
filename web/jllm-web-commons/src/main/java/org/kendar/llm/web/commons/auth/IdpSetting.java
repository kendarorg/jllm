package org.kendar.llm.web.commons.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The {@value #KEY} setting of a role: which identity provider groups give the role at sign-in.
 * {@code {"groups": ["engineering", "team-*"], "priority": 10}}.
 *
 * @param groups   exact names or {@code *} globs, case-sensitive; at most {@value #MAX_GROUPS}, each 1-{@value #MAX_LENGTH}
 *                 characters
 * @param priority lower wins when several roles match; ties go to the role name, alphabetically (default
 *                 {@value #DEFAULT_PRIORITY})
 */
public record IdpSetting(List<String> groups, int priority) {

    public static final String KEY = "idp";
    public static final int MAX_GROUPS = 100;
    public static final int MAX_LENGTH = 256;
    public static final int DEFAULT_PRIORITY = 100;
    private static final Set<String> KEYS = Set.of("groups", "priority");

    public IdpSetting {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * Strict reading of the setting.
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public static IdpSetting parse(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("Settings 'idp' must be an object {groups: [...], priority: N}");
        }
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!KEYS.contains(k)) throw new IllegalArgumentException("Unknown key '" + k + "' in settings 'idp'");
        }
        List<String> groups = new ArrayList<>();
        JsonNode g = n.get("groups");
        if (g != null && !g.isNull()) {
            if (!g.isArray()) throw new IllegalArgumentException("'idp.groups' must be an array of strings");
            if (g.size() > MAX_GROUPS) {
                throw new IllegalArgumentException("'idp.groups' has more than " + MAX_GROUPS + " entries");
            }
            for (JsonNode e : g) {
                if (!e.isTextual()) throw new IllegalArgumentException("'idp.groups' must be an array of strings");
                String s = e.asText().strip();
                if (s.isEmpty() || s.length() > MAX_LENGTH) {
                    throw new IllegalArgumentException("Each of 'idp.groups' must be 1-" + MAX_LENGTH + " characters");
                }
                if (!groups.contains(s)) groups.add(s);
            }
        }
        int priority = DEFAULT_PRIORITY;
        JsonNode p = n.get("priority");
        if (p != null && !p.isNull()) {
            if (!p.isIntegralNumber() || !p.canConvertToInt()) {
                throw new IllegalArgumentException("'idp.priority' must be an integer");
            }
            priority = p.intValue();
        }
        return new IdpSetting(groups, priority);
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        ArrayNode a = o.putArray("groups");
        groups.forEach(a::add);
        o.put("priority", priority);
        return o;
    }

    /** The first pattern that matches {@code group}, or null. */
    public String patternFor(String group) {
        for (String p : groups) if (glob(p, group)) return p;
        return null;
    }

    /** Case-sensitive match of the whole text, {@code *} = any run of characters; no regex, so no pattern can blow up. */
    static boolean glob(String pattern, String text) {
        if (pattern == null || text == null) return false;
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 1) return text.equals(pattern);
        if (!text.startsWith(parts[0])) return false;
        int pos = parts[0].length();
        String last = parts[parts.length - 1];
        int end = text.length() - last.length();
        if (end < pos) return false;
        for (int i = 1; i < parts.length - 1; i++) {
            int at = text.indexOf(parts[i], pos);
            if (at < 0 || at + parts[i].length() > end) return false;
            pos = at + parts[i].length();
        }
        return text.endsWith(last);
    }
}

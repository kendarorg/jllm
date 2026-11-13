package org.kendar.llm.harness.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Visibility of the tools of an external source (MCP server, REST API) from an allow list and a deny list of
 * {@link Wildcard} patterns. A {@code null} list is unset; an empty list is a list with no entries.
 * <ul>
 *   <li>allow unset and deny unset/empty: every tool is visible;</li>
 *   <li>allow set (an empty list allows nothing, {@code ["*"]} everything): the tool must match an entry;</li>
 *   <li>deny set: a tool matching an entry is hidden ({@code ["*"]} hides everything).</li>
 * </ul>
 * Both lists apply together: a tool is visible when it is allowed and not denied.
 */
public final class ToolFilter {

    private ToolFilter() {
    }

    public static boolean visible(List<String> allow, List<String> deny, String key) {
        if (allow != null && !Wildcard.matchesAny(allow, key)) return false;
        return !Wildcard.matchesAny(deny, key);
    }

    /** REST entries are {@code "METHOD /path"}; an entry without a method matches any method. */
    public static List<String> restKeys(List<String> entries) {
        if (entries == null) return null;
        List<String> out = new ArrayList<>(entries.size());
        for (String e : entries) {
            String t = e.trim();
            int sp = t.indexOf(' ');
            boolean hasMethod = sp > 0 && t.substring(0, sp).chars().allMatch(Character::isLetter);
            out.add(hasMethod ? t : "* " + t);
        }
        return out;
    }
}

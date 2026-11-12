package org.kendar.llm.harness.util;

import java.util.List;
import java.util.Locale;

/** Case-insensitive matching where {@code *} stands for any run of characters (including none), e.g. {@code *key*test}. */
public final class Wildcard {

    private Wildcard() {
    }

    /** Whether the whole of {@code text} matches {@code pattern}; no regex is involved, so no pattern can blow up. */
    public static boolean matches(String pattern, String text) {
        if (pattern == null || text == null) return false;
        String p = pattern.toLowerCase(Locale.ROOT);
        String t = text.toLowerCase(Locale.ROOT);
        String[] parts = p.split("\\*", -1);
        if (parts.length == 1) return t.equals(p);
        if (!t.startsWith(parts[0])) return false;
        int pos = parts[0].length();
        String last = parts[parts.length - 1];
        int end = t.length() - last.length();
        if (end < pos) return false;
        for (int i = 1; i < parts.length - 1; i++) {
            int at = t.indexOf(parts[i], pos);
            if (at < 0 || at + parts[i].length() > end) return false;
            pos = at + parts[i].length();
        }
        return t.endsWith(last);
    }

    /** Whether any of the patterns matches; an empty or null list matches nothing. */
    public static boolean matchesAny(List<String> patterns, String text) {
        if (patterns == null) return false;
        for (String p : patterns) if (matches(p, text)) return true;
        return false;
    }
}

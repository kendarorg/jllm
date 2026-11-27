package org.kendar.llm.eval.pin;

import java.util.Locale;

/** What a run does when tracked files differ from HEAD ({@code eval.dirtyTree}). */
public enum DirtyTreePolicy {
    /** Stop before the warm-up call, listing the changed files. */
    REFUSE,
    /** Run, show a banner and mark run metadata, summary and report. */
    WARN,
    /** Run without a banner; the metadata still records the state. */
    ALLOW;

    public static DirtyTreePolicy parse(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("must be refuse, warn or allow (got '" + value + "')");
        }
    }

    /** Lower case name, as written in the settings. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}

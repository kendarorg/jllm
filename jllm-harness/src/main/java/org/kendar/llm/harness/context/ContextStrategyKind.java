package org.kendar.llm.harness.context;

import java.util.Locale;

/** The built-in context strategies. */
public enum ContextStrategyKind {
    /** Tiered compaction: clear old tool results, summarize, user-approved rewrite. Rewrites the log. */
    COMPACTION,
    /** Sticky masking of old content into receipts, segment handoffs and lexical recall. The log stays verbatim. */
    MNEMONIC,
    /** A strategy supplied through the builder. */
    CUSTOM;

    /** @throws IllegalArgumentException on an unknown name */
    public static ContextStrategyKind parse(String s) {
        if (s == null || s.isBlank()) return COMPACTION;
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "compaction", "old", "default" -> COMPACTION;
            case "mnemonic" -> MNEMONIC;
            default -> throw new IllegalArgumentException("Unknown context strategy '" + s
                    + "' (use compaction or mnemonic)");
        };
    }
}

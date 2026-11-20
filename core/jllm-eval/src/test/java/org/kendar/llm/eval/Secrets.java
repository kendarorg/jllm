package org.kendar.llm.eval;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The secret values of one configuration. Everything printed, logged or written goes through {@link #mask}. Immutable:
 * each {@link EvalConfig} owns its own, so tests loading their own configuration never see or clear another's.
 */
public final class Secrets {

    public static final String MASK = "***";
    public static final Secrets NONE = new Secrets(List.of());

    /** Longest first, so a secret containing another is masked whole. */
    private final List<String> values;

    public Secrets(Collection<String> values) {
        this.values = values.stream().filter(v -> v != null && !v.isBlank()).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    public String mask(String text) {
        if (text == null) return null;
        String out = text;
        for (String v : values) out = out.replace(v, MASK);
        return out;
    }
}

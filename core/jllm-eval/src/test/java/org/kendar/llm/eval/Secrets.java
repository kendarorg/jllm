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

    /**
     * Placeholder keys ({@code none} for llama.cpp and ollama, anything shorter than {@value #MIN_LENGTH} characters)
     * are not secrets: masking them would replace ordinary words in answers and traces.
     */
    static final int MIN_LENGTH = 8;

    public Secrets(Collection<String> values) {
        this.values = values.stream().filter(v -> v != null && v.strip().length() >= MIN_LENGTH).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    public String mask(String text) {
        if (text == null) return null;
        String out = text;
        for (String v : values) out = out.replace(v, MASK);
        return out;
    }

    /**
     * As {@link #mask}, for serialized JSON: an occurrence that starts on the letter of an escape sequence ({@code \n}
     * followed by the rest of the secret) is text, not the secret, and replacing it would break the JSON.
     */
    public String maskJson(String json) {
        if (json == null) return null;
        String out = json;
        for (String v : values) {
            StringBuilder sb = new StringBuilder(out.length());
            int from = 0;
            int i;
            while ((i = out.indexOf(v, from)) >= 0) {
                int backslashes = 0;
                for (int j = i - 1; j >= 0 && out.charAt(j) == '\\'; j--) backslashes++;
                if (backslashes % 2 == 1) {
                    sb.append(out, from, i + 1);
                    from = i + 1;
                } else {
                    sb.append(out, from, i).append(MASK);
                    from = i + v.length();
                }
            }
            out = sb.append(out, from, out.length()).toString();
        }
        return out;
    }
}

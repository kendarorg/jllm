package org.kendar.llm.eval;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of secret values. Everything printed, logged or written goes through {@link #mask}. */
public final class Secrets {

    public static final String MASK = "***";
    private static final Set<String> VALUES = ConcurrentHashMap.newKeySet();

    private Secrets() {
    }

    public static void register(String secret) {
        if (secret != null && !secret.isBlank()) VALUES.add(secret);
    }

    /** Test hook. */
    public static void clear() {
        VALUES.clear();
    }

    public static String mask(String text) {
        if (text == null) return null;
        String out = text;
        for (String v : VALUES.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            out = out.replace(v, MASK);
        }
        return out;
    }
}

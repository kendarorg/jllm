package org.kendar.llm.harness.util;

import java.util.List;
import java.util.Map;

/**
 * Which environment variables of the harness process a child process (a shell command, an MCP server) may inherit.
 * Patterns use {@link Wildcard}; {@code ["*"]} (the default) lets everything through, {@code []} nothing.
 */
public final class EnvWhitelist {

    public static final List<String> ALL = List.of("*");

    private EnvWhitelist() {
    }

    /** {@code null} (setting absent) means everything. */
    public static List<String> orAll(List<String> patterns) {
        return patterns == null ? ALL : List.copyOf(patterns);
    }

    public static boolean allows(List<String> patterns, String name) {
        return Wildcard.matchesAny(patterns, name);
    }

    /** Removes from {@code env} (typically {@code ProcessBuilder.environment()}) every variable not whitelisted. */
    public static void apply(Map<String, String> env, List<String> patterns) {
        if (patterns != null && patterns.contains("*")) return;
        env.keySet().removeIf(name -> !allows(patterns, name));
    }
}

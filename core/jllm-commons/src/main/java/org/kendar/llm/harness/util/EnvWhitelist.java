package org.kendar.llm.harness.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which environment variables of the harness process a child process (a shell command, an MCP server) may inherit.
 * Patterns use {@link Wildcard}; the default is {@code PATH, HOME, LANG, TERM}, {@code []} nothing. Whatever the
 * patterns say, secrets never pass: {@code JLLM_*} and every name containing {@code KEY}, {@code SECRET} or
 * {@code PASSWORD} (case-insensitive) are stripped ({@link #isSecret}).
 */
public final class EnvWhitelist {

    /** The names inherited when the setting is absent. */
    public static final List<String> DEFAULT = List.of("PATH", "HOME", "LANG", "TERM");

    /** What a Windows process cannot start without. */
    private static final List<String> WINDOWS_ESSENTIAL = List.of("SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP");

    private EnvWhitelist() {
    }

    /** {@link #DEFAULT}, plus the variables Windows needs to run anything there. */
    public static List<String> defaults() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return DEFAULT;
        List<String> l = new ArrayList<>(DEFAULT);
        l.addAll(WINDOWS_ESSENTIAL);
        return List.copyOf(l);
    }

    /** {@code null} (setting absent) means the {@link #defaults()}. */
    public static List<String> orDefault(List<String> patterns) {
        return patterns == null ? defaults() : List.copyOf(patterns);
    }

    /** True for {@code JLLM_*} and names containing {@code KEY}, {@code SECRET} or {@code PASSWORD}, any case. */
    public static boolean isSecret(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        return n.startsWith("JLLM_") || n.contains("KEY") || n.contains("SECRET") || n.contains("PASSWORD");
    }

    /** Whether the patterns let {@code name} through: matched and not a secret. */
    public static boolean allows(List<String> patterns, String name) {
        return !isSecret(name) && Wildcard.matchesAny(patterns, name);
    }

    /**
     * Removes from {@code env} (typically {@code ProcessBuilder.environment()}) every variable not whitelisted and
     * every secret, whitelisted or not.
     */
    public static void apply(Map<String, String> env, List<String> patterns) {
        env.keySet().removeIf(name -> !allows(patterns, name));
    }
}

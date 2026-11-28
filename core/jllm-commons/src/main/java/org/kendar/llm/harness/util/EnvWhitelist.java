package org.kendar.llm.harness.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which environment variables of the harness process a child process (a shell command, an MCP server) may inherit.
 * Patterns use {@link Wildcard}; the default is {@code PATH, HOME, LANG, TERM}, {@code []} nothing. Whatever the
 * patterns say, secrets never pass: {@code JLLM_*}, every name containing {@code KEY}, {@code SECRET},
 * {@code PASSWORD}, {@code PASSWD}, {@code TOKEN}, {@code CREDENTIAL} or {@code AUTH} (case-insensitive), and every
 * variable whose value is a URL carrying credentials ({@code scheme://user:password@host}) are stripped
 * ({@link #isSecret(String, String)}).
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

    /** Name fragments that mark a secret, upper case. */
    private static final List<String> SECRET_FRAGMENTS = List.of(
            "KEY", "SECRET", "PASSWORD", "PASSWD", "TOKEN", "CREDENTIAL", "AUTH");

    /** {@code scheme://user:password@host}: a URL that carries a credential. */
    private static final Pattern CREDENTIAL_URL = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://[^/\\s@:]*:[^/\\s@]+@");

    /** True for {@code JLLM_*} and names containing one of {@link #SECRET_FRAGMENTS}, any case. */
    public static boolean isSecret(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        if (n.startsWith("JLLM_")) return true;
        for (String f : SECRET_FRAGMENTS) if (n.contains(f)) return true;
        return false;
    }

    /** True when {@code value} contains a URL with a user and a password ({@code https://u:p@host}). */
    public static boolean holdsCredentials(String value) {
        return value != null && value.contains("@") && CREDENTIAL_URL.matcher(value).find();
    }

    /** A secret by its name ({@link #isSecret(String)}) or by its value ({@link #holdsCredentials}). */
    public static boolean isSecret(String name, String value) {
        return isSecret(name) || holdsCredentials(value);
    }

    /** Whether the patterns let the variable through: its name matched and neither name nor value a secret. */
    public static boolean allows(List<String> patterns, String name, String value) {
        return !isSecret(name, value) && Wildcard.matchesAny(patterns, name);
    }

    /**
     * Removes from {@code env} (typically {@code ProcessBuilder.environment()}) every variable not whitelisted and
     * every secret, whitelisted or not.
     */
    public static void apply(Map<String, String> env, List<String> patterns) {
        env.entrySet().removeIf(e -> !allows(patterns, e.getKey(), e.getValue()));
    }
}

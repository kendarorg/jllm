package org.kendar.llm.js;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code plugins.js.settings}.
 *
 * @param statementLimit     statements a script may execute (constrained profile; the isolate relies on the CPU limit)
 * @param maxTimeoutSeconds  ceiling of the per-call {@code timeoutSeconds}; the clock stands still during tool calls
 * @param gracefulSeconds    time a script gets after the user stopped the run before it is killed
 * @param fileJail           file tools called by a script work in the chat's scratch directory only
 * @param maxWallSeconds     total time of a script, tool calls included; never paused
 * @param maxInnerCalls      tool calls one script may make
 * @param maxConcurrentRuns  scripts running at once; zero is unlimited
 * @param engine             which profile runs the scripts
 * @param policy             sandbox policy of the isolated profile
 * @param maxHeapMemory      bytes of script heap (isolated profile, or constrained where the runtime counts it)
 * @param maxIsolateMemory   bytes of the whole isolate
 * @param maxStackFrames     script call depth
 * @param maxAstDepth        nesting depth of the parsed script
 * @param maxRunAllocatedBytes allocation budget of one run (memory watchdog of the constrained profile)
 * @param heapKillRatio      share of the JVM's old generation, after a collection, above which the watchdog kills the
 *                           biggest allocating run
 */
public record JsSettings(long statementLimit, int maxTimeoutSeconds, int gracefulSeconds, boolean fileJail,
                         int maxWallSeconds, int maxInnerCalls, int maxConcurrentRuns, EngineChoice engine, Policy policy,
                         long maxHeapMemory, long maxIsolateMemory, int maxStackFrames, int maxAstDepth,
                         long maxRunAllocatedBytes, double heapKillRatio) {

    /** The {@code engine} setting. */
    public enum EngineChoice {
        /** Isolate if the runtime has one, else constrained. */
        AUTO,
        CONSTRAINED,
        /** Disables the tool, with a reason, when there is no isolate: never a silent downgrade. */
        ISOLATED
    }

    /** The {@code policy} setting: the sandbox policy of the isolated profile. */
    public enum Policy {
        ISOLATED,
        /** The strictest policy; the default of the web application. */
        UNTRUSTED
    }

    private static final String PREFIX = "plugins.js.settings.";
    private static final long MIB = 1024L * 1024L;

    public static JsSettings defaults() {
        return new JsSettings(1_000_000L, 120, 10, true, 300, 200, 4, EngineChoice.AUTO, Policy.ISOLATED,
                256 * MIB, 1024 * MIB, 2000, 1000, 1024 * MIB, 0.85);
    }

    /**
     * @throws IllegalArgumentException naming the key, for an unknown enum value, a non-positive number or a value of
     *                                  the wrong type
     */
    public static JsSettings from(JsonNode n) {
        JsSettings d = defaults();
        if (n == null || !n.isObject()) return d;
        return new JsSettings(
                positiveLong(n, "statementLimit", d.statementLimit()),
                positiveInt(n, "maxTimeoutSeconds", d.maxTimeoutSeconds()),
                positiveInt(n, "gracefulSeconds", d.gracefulSeconds()),
                bool(n, "fileJail", d.fileJail()),
                positiveInt(n, "maxWallSeconds", d.maxWallSeconds()),
                positiveInt(n, "maxInnerCalls", d.maxInnerCalls()),
                nonNegativeInt(n, "maxConcurrentRuns", d.maxConcurrentRuns()),
                enumValue(n, "engine", EngineChoice.class, d.engine()),
                enumValue(n, "policy", Policy.class, d.policy()),
                size(n, "maxHeapMemory", d.maxHeapMemory()),
                size(n, "maxIsolateMemory", d.maxIsolateMemory()),
                positiveInt(n, "maxStackFrames", d.maxStackFrames()),
                positiveInt(n, "maxAstDepth", d.maxAstDepth()),
                size(n, "maxRunAllocatedBytes", d.maxRunAllocatedBytes()),
                ratio(n, "heapKillRatio", d.heapKillRatio()));
    }

    private static IllegalArgumentException bad(String key, String what) {
        return new IllegalArgumentException("Invalid setting " + PREFIX + key + ": " + what);
    }

    private static boolean bool(JsonNode n, String key, boolean d) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return d;
        if (!v.isBoolean()) throw bad(key, "expected true or false, got " + v);
        return v.asBoolean();
    }

    private static long positiveLong(JsonNode n, String key, long d) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return d;
        if (!v.isIntegralNumber()) throw bad(key, "expected a whole number, got " + v);
        if (v.asLong() <= 0) throw bad(key, "must be greater than zero, got " + v);
        return v.asLong();
    }

    private static int positiveInt(JsonNode n, String key, int d) {
        long v = positiveLong(n, key, d);
        if (v > Integer.MAX_VALUE) throw bad(key, "too large: " + v);
        return (int) v;
    }

    private static int nonNegativeInt(JsonNode n, String key, int d) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return d;
        if (!v.isIntegralNumber()) throw bad(key, "expected a whole number, got " + v);
        if (v.asLong() < 0 || v.asLong() > Integer.MAX_VALUE) throw bad(key, "must be zero or more, got " + v);
        return v.asInt();
    }

    private static double ratio(JsonNode n, String key, double d) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return d;
        if (!v.isNumber()) throw bad(key, "expected a number, got " + v);
        double r = v.asDouble();
        if (!(r > 0 && r <= 1)) throw bad(key, "must be above 0 and at most 1, got " + v);
        return r;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode n, String key, Class<E> type, E d) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return d;
        String s = v.isTextual() ? v.asText().strip().toUpperCase(Locale.ROOT) : "";
        for (E e : type.getEnumConstants()) if (e.name().equals(s)) return e;
        throw bad(key, "expected one of " + Arrays.stream(type.getEnumConstants())
                .map(e -> e.name().toLowerCase(Locale.ROOT)).toList() + ", got " + v);
    }

    /** A number of bytes, or text with a unit: {@code 256MB}, {@code 1GB}, {@code 512KB}. */
    private static long size(JsonNode n, String key, long d) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return d;
        if (v.isIntegralNumber()) {
            if (v.asLong() <= 0) throw bad(key, "must be greater than zero, got " + v);
            return v.asLong();
        }
        if (v.isTextual()) {
            String s = v.asText().strip().toUpperCase(Locale.ROOT);
            long unit = 1;
            String digits = s;
            for (String[] u : new String[][]{{"KB", "1024"}, {"MB", "1048576"}, {"GB", "1073741824"}, {"B", "1"}}) {
                if (s.endsWith(u[0])) {
                    unit = Long.parseLong(u[1]);
                    digits = s.substring(0, s.length() - u[0].length()).strip();
                    break;
                }
            }
            try {
                long amount = Long.parseLong(digits);
                if (amount > 0 && amount <= Long.MAX_VALUE / unit) return amount * unit;
            } catch (NumberFormatException ignored) {
                // falls through to the error
            }
        }
        throw bad(key, "expected a positive number of bytes or a size such as 256MB, got " + v);
    }
}

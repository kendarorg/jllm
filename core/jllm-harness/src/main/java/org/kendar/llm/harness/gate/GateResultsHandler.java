package org.kendar.llm.harness.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a structured result into one response from a closed list. Reads one typed value (a JSON field or a process
 * exit code) and looks it up in a value-to-response map; it never reads prose and never calls a model. A value that
 * is missing, has the wrong type or is not in the map gives the fallback, with the reason recorded.
 */
public final class GateResultsHandler {

    /** Key of the map entry that matches any value not listed. */
    public static final String ANY = "*";

    public enum ValueType {
        BOOLEAN, STRING, NUMBER, EXIT_CODE;

        /** @param s {@code boolean}, {@code string}, {@code number} or {@code exitCode} (case and {@code _} ignored) */
        public static ValueType parse(String s) {
            String n = s == null ? "" : s.strip().replace("_", "").toLowerCase(Locale.ROOT);
            return switch (n) {
                case "boolean" -> BOOLEAN;
                case "string" -> STRING;
                case "number" -> NUMBER;
                case "exitcode" -> EXIT_CODE;
                default -> throw new IllegalArgumentException("type must be boolean, string, number or exitCode, not '" + s + "'");
            };
        }
    }

    /**
     * @param field    the field to read, dotted for nested objects; null or blank = the whole value
     * @param map      value (as text) to response; {@link #ANY} matches any other value of the right type
     * @param fallback response for a missing, wrong-type or unmapped value
     * @param allowed  responses that may be given; null = the map's responses plus the fallback
     */
    public record Config(String field, ValueType type, Map<String, String> map, String fallback, Set<String> allowed) {
        public Config {
            if (type == null) throw new IllegalArgumentException("type is required");
            if (map == null || map.isEmpty()) throw new IllegalArgumentException("map must have at least one entry");
            if (fallback == null || fallback.isBlank()) throw new IllegalArgumentException("fallback is required");
            field = field == null || field.isBlank() ? null : field.strip();
            for (var e : map.entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank()) {
                    throw new IllegalArgumentException("map entry '" + e.getKey() + "' has no response");
                }
                checkKey(type, e.getKey());
            }
            map = Collections.unmodifiableMap(new LinkedHashMap<>(map));
            if (allowed == null) {
                Set<String> all = new LinkedHashSet<>(map.values());
                all.add(fallback);
                allowed = Collections.unmodifiableSet(all);
            } else {
                allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
                for (String r : map.values()) {
                    if (!allowed.contains(r)) throw new IllegalArgumentException("response '" + r + "' is not allowed: " + allowed);
                }
                if (!allowed.contains(fallback)) {
                    throw new IllegalArgumentException("fallback '" + fallback + "' is not allowed: " + allowed);
                }
            }
        }

        private static void checkKey(ValueType type, String key) {
            if (ANY.equals(key)) return;
            switch (type) {
                case BOOLEAN -> {
                    if (!key.equals("true") && !key.equals("false")) {
                        throw new IllegalArgumentException("boolean map key must be true, false or *, not '" + key + "'");
                    }
                }
                case NUMBER -> {
                    try {
                        new BigDecimal(key);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("number map key '" + key + "' is not a number");
                    }
                }
                case EXIT_CODE -> {
                    try {
                        Integer.parseInt(key);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("exit code map key '" + key + "' is not a whole number");
                    }
                }
                case STRING -> {
                }
            }
        }

        /**
         * Reads {@code field}, {@code type}, {@code map} (object of text responses), {@code fallback} and
         * {@code allowed} (list); other keys, such as {@code from}, are ignored.
         *
         * @throws IllegalArgumentException when the configuration is incomplete or inconsistent
         */
        public static Config fromJson(JsonNode with) {
            if (with == null || !with.isObject()) throw new IllegalArgumentException("gate configuration must be an object");
            JsonNode m = with.path("map");
            if (!m.isObject()) throw new IllegalArgumentException("map must be an object of value to response");
            Map<String, String> map = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = m.fields(); it.hasNext(); ) {
                var e = it.next();
                if (!e.getValue().isTextual()) throw new IllegalArgumentException("map entry '" + e.getKey() + "' must be text");
                map.put(e.getKey(), e.getValue().asText());
            }
            JsonNode fb = with.path("fallback");
            if (!fb.isTextual()) throw new IllegalArgumentException("fallback is required");
            Set<String> allowed = null;
            JsonNode a = with.path("allowed");
            if (!a.isMissingNode()) {
                if (!a.isArray()) throw new IllegalArgumentException("allowed must be a list of responses");
                allowed = new LinkedHashSet<>();
                for (JsonNode n : a) {
                    if (!n.isTextual()) throw new IllegalArgumentException("allowed must be a list of responses");
                    allowed.add(n.asText());
                }
            }
            JsonNode f = with.path("field");
            if (!f.isMissingNode() && !f.isTextual()) throw new IllegalArgumentException("field must be text");
            return new Config(f.isTextual() ? f.asText() : null, ValueType.parse(with.path("type").asText(null)), map,
                    fb.asText(), allowed);
        }
    }

    /**
     * @param response the chosen response, always one of the allowed ones
     * @param value    the value it came from; JSON null when it was missing
     * @param fellBack true when the fallback was used
     * @param reason   why this response, for the journal
     */
    public record Result(String response, JsonNode value, boolean fellBack, String reason) {
    }

    private final Config config;

    public GateResultsHandler(Config config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /** The responses this handler can give: the map's responses plus the fallback. */
    public Set<String> responses() {
        Set<String> r = new LinkedHashSet<>(config.map().values());
        r.add(config.fallback());
        return Collections.unmodifiableSet(r);
    }

    /** Decides from a structured result, reading the configured field (or the whole result when none is set). */
    public Result decide(JsonNode result) {
        String where = config.field() == null ? "value" : "field '" + config.field() + "'";
        JsonNode v = result == null ? NullNode.getInstance() : result;
        if (config.field() != null) {
            for (String part : config.field().split("[.]")) v = v.path(part);
        }
        if (v.isMissingNode() || v.isNull()) return fallback(NullNode.getInstance(), where + " is missing");
        return lookup(v, where);
    }

    /** Decides from a process exit code; the code is the value, no field is read. */
    public Result decide(int exitCode) {
        return lookup(IntNode.valueOf(exitCode), "exit code");
    }

    private Result lookup(JsonNode v, String where) {
        String key = keyOf(v);
        if (key == null) {
            return fallback(v, where + " is " + kind(v) + ", not " + config.type().name().toLowerCase(Locale.ROOT));
        }
        String r = config.map().get(key);
        if (r == null && config.type() == ValueType.NUMBER) r = numeric(v);
        if (r != null) return new Result(r, v, false, where + " = " + key + " -> " + r);
        r = config.map().get(ANY);
        if (r != null) return new Result(r, v, false, where + " = " + key + " matches * -> " + r);
        return fallback(v, where + " = " + key + " is not mapped");
    }

    /** The map key the value is looked up by, or null when the value has the wrong type. */
    private String keyOf(JsonNode v) {
        return switch (config.type()) {
            case BOOLEAN -> v.isBoolean() ? String.valueOf(v.booleanValue()) : null;
            case STRING -> v.isTextual() ? v.textValue() : null;
            case NUMBER -> v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : null;
            case EXIT_CODE -> v.isIntegralNumber() && v.canConvertToInt() ? String.valueOf(v.intValue()) : null;
        };
    }

    /** Numeric keys match by value, so {@code 1}, {@code 1.0} and {@code 1e0} are the same. */
    private String numeric(JsonNode v) {
        BigDecimal d = v.decimalValue();
        for (var e : config.map().entrySet()) {
            if (!ANY.equals(e.getKey()) && new BigDecimal(e.getKey()).compareTo(d) == 0) return e.getValue();
        }
        return null;
    }

    private Result fallback(JsonNode v, String why) {
        return new Result(config.fallback(), v, true, why + " -> fallback " + config.fallback());
    }

    private static String kind(JsonNode v) {
        return switch (v.getNodeType()) {
            case BOOLEAN -> "a boolean";
            case STRING -> "a string";
            case NUMBER -> "a number";
            case ARRAY -> "an array";
            case OBJECT -> "an object";
            default -> v.getNodeType().name().toLowerCase(Locale.ROOT);
        };
    }
}

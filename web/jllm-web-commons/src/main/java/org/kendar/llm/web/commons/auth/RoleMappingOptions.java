package org.kendar.llm.web.commons.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The global options of the identity provider role mapping, as they apply now.
 *
 * @param enabled    off: no claim is read and no role is set at sign-in
 * @param claim      claim path in the ID token; a dotted path walks nested objects ({@code realm_access.roles})
 * @param onNoMatch  what happens when no role matches
 * @param allowAdmin whether a group can grant the {@code admin} role
 * @param maxAge     an identity provider session older than this must sign in again (only while enabled); null = none
 */
public record RoleMappingOptions(boolean enabled, String claim, OnNoMatch onNoMatch, boolean allowAdmin,
                                 Duration maxAge) {

    public static final String DEFAULT_CLAIM = "groups";
    public static final Duration MIN_AGE = Duration.ofMinutes(5);
    public static final Duration MAX_AGE = Duration.ofDays(30);
    private static final Pattern CLAIM = Pattern.compile("[\\x21-\\x7e]{1,256}");
    private static final Pattern SIMPLE = Pattern.compile("(\\d{1,9})\\s*([smhd])");
    private static final Set<String> KEYS = Set.of("enabled", "claim", "onNoMatch", "allowAdmin", "maxAge");

    /** What a sign-in does when no role matches the groups. */
    public enum OnNoMatch {
        /** The default role ({@code jllm.auth.oidc.default-role}): for new users and existing ones. */
        DEFAULT,
        /** The current role stays; a new user gets the default role. */
        KEEP,
        /** The sign-in is refused with {@code no_role}. */
        REFUSE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** @throws IllegalArgumentException for anything but default, keep or refuse */
        public static OnNoMatch parse(String s) {
            if (s == null || s.isBlank()) return DEFAULT;
            return switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "default" -> DEFAULT;
                case "keep" -> KEEP;
                case "refuse" -> REFUSE;
                default -> throw new IllegalArgumentException("'onNoMatch' must be default, keep or refuse, not " + s);
            };
        }
    }

    public RoleMappingOptions {
        claim = claim == null || claim.isBlank() ? DEFAULT_CLAIM : claim.strip();
        if (!CLAIM.matcher(claim).matches()) {
            throw new IllegalArgumentException("'claim' must be 1-256 printable characters without spaces");
        }
        onNoMatch = onNoMatch == null ? OnNoMatch.DEFAULT : onNoMatch;
        if (maxAge != null && (maxAge.compareTo(MIN_AGE) < 0 || maxAge.compareTo(MAX_AGE) > 0)) {
            throw new IllegalArgumentException("'maxAge' must be between 5m and 30d");
        }
    }

    /**
     * The options of the properties.
     *
     * @throws IllegalStateException naming the key that is wrong
     */
    public static RoleMappingOptions of(RoleMappingProperties p) {
        RoleMappingProperties r = p == null ? RoleMappingProperties.NONE : p;
        OnNoMatch onNoMatch;
        try {
            onNoMatch = OnNoMatch.parse(r.onNoMatch());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("jllm.auth.oidc.role-mapping.on-no-match must be default, keep or refuse, not "
                    + r.onNoMatch());
        }
        try {
            return new RoleMappingOptions(Boolean.TRUE.equals(r.enabled()), r.claim(), onNoMatch,
                    Boolean.TRUE.equals(r.allowAdmin()), r.maxAge());
        } catch (IllegalArgumentException e) {
            String key = e.getMessage().contains("'claim'") ? "claim" : "max-age";
            throw new IllegalStateException("jllm.auth.oidc.role-mapping." + key + ": " + e.getMessage());
        }
    }

    /**
     * The options of a JSON object; a key that is absent keeps its value in {@code base}. Strict: an unknown key or a
     * value of the wrong type is refused.
     *
     * @throws IllegalArgumentException naming the key that is wrong
     */
    public static RoleMappingOptions parse(JsonNode n, RoleMappingOptions base) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("The role mapping options must be a JSON object");
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!KEYS.contains(k)) throw new IllegalArgumentException("Unknown role mapping option '" + k + "'");
        }
        boolean enabled = bool(n, "enabled", base.enabled());
        boolean allowAdmin = bool(n, "allowAdmin", base.allowAdmin());
        String claim = base.claim();
        if (n.has("claim")) {
            if (!n.get("claim").isTextual()) throw new IllegalArgumentException("'claim' must be a string");
            claim = n.get("claim").asText();
        }
        OnNoMatch onNoMatch = base.onNoMatch();
        if (n.has("onNoMatch")) {
            if (!n.get("onNoMatch").isTextual()) throw new IllegalArgumentException("'onNoMatch' must be a string");
            onNoMatch = OnNoMatch.parse(n.get("onNoMatch").asText());
        }
        Duration maxAge = base.maxAge();
        if (n.has("maxAge")) {
            JsonNode v = n.get("maxAge");
            if (v.isNull() || (v.isTextual() && v.asText().isBlank())) maxAge = null;
            else if (v.isTextual()) maxAge = duration(v.asText());
            else throw new IllegalArgumentException("'maxAge' must be a duration such as 8h, or null");
        }
        return new RoleMappingOptions(enabled, claim, onNoMatch, allowAdmin, maxAge);
    }

    private static boolean bool(JsonNode n, String key, boolean current) {
        if (!n.has(key)) return current;
        if (!n.get(key).isBoolean()) throw new IllegalArgumentException("'" + key + "' must be true or false");
        return n.get(key).asBoolean();
    }

    /** {@code 30m}, {@code 8h}, {@code 7d}, {@code 90s} or ISO-8601 ({@code PT8H}). */
    static Duration duration(String s) {
        String t = s.strip().toLowerCase(Locale.ROOT);
        Matcher m = SIMPLE.matcher(t);
        if (m.matches()) {
            long v = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(v);
                case "m" -> Duration.ofMinutes(v);
                case "h" -> Duration.ofHours(v);
                default -> Duration.ofDays(v);
            };
        }
        try {
            return Duration.parse(s.strip().toUpperCase(Locale.ROOT));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'maxAge' must be a duration such as 30m, 8h or 7d, not " + s);
        }
    }

    /** The shortest of {@code 7d}, {@code 8h}, {@code 30m}, {@code 90s} that is exact. */
    static String format(Duration d) {
        long s = d.getSeconds();
        if (s % 86400 == 0) return s / 86400 + "d";
        if (s % 3600 == 0) return s / 3600 + "h";
        if (s % 60 == 0) return s / 60 + "m";
        return s + "s";
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("enabled", enabled);
        o.put("claim", claim);
        o.put("onNoMatch", onNoMatch.wire());
        o.put("allowAdmin", allowAdmin);
        if (maxAge == null) o.putNull("maxAge");
        else o.put("maxAge", format(maxAge));
        return o;
    }
}

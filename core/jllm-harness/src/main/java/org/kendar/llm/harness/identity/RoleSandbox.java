package org.kendar.llm.harness.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.sandbox.SandboxKind;

/**
 * The sandbox an admin gives a role, kept as the {@code sandbox} key of the role settings:
 * {@code {"kind": ..., "scope": ..., "network": ..., "localFallback": ...}}. Null container fields keep the configured
 * defaults.
 *
 * @param kind          none (default), shared-local (confined host processes), docker-isolated (hardened container) or
 *                      docker-open (standard container)
 * @param scope         docker: {@code chat} (one container per chat) or {@code user} (one per user)
 * @param network       docker-isolated: {@code none} or {@code default}; shared-local: {@code none} (default) or
 *                      {@code default} (network granted; {@code false} and {@code true} are read the same); ignored for
 *                      the other kinds
 * @param localFallback docker kinds: when docker is unavailable the chat may continue in a {@code shared-local}
 *                      sandbox (never on the plain host); default false
 */
public record RoleSandbox(SandboxKind kind, String scope, String network, boolean localFallback) {

    public static final RoleSandbox NONE = new RoleSandbox(SandboxKind.NONE, null, null, false);

    public RoleSandbox {
        kind = kind == null ? SandboxKind.NONE : kind;
        scope = kind.docker() ? check("scope", scope, "chat", "user") : null;
        network = kind == SandboxKind.DOCKER_ISOLATED ? check("network", network, "none", "default")
                : kind == SandboxKind.SHARED_LOCAL ? check("network", bool(network), "none", "default") : null;
        localFallback = kind.docker() && localFallback;
    }

    private static String bool(String v) {
        if (v == null) return null;
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> "default";
            case "false" -> "none";
            default -> v;
        };
    }

    /** True when a shared-local sandbox gives the role network access. */
    public boolean networkGranted() {
        return kind == SandboxKind.SHARED_LOCAL && "default".equals(network);
    }

    /** The container profile this sandbox implies ({@code hardened} or {@code standard}); null when not docker. */
    public String profile() {
        return kind.profile();
    }

    private static String check(String what, String v, String a, String b) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (!s.equals(a) && !s.equals(b)) throw new IllegalArgumentException("sandbox " + what + " must be " + a + " or " + b);
        return s;
    }

    public static RoleSandbox of(String kind, String scope, String network, boolean localFallback) {
        return new RoleSandbox(SandboxKind.parse(kind), scope, network, localFallback);
    }

    /** The settings value: {@code {"kind": ..., "scope": ..., "network": ..., "localFallback": ...}} (null fields left out). */
    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("kind", kind.wire());
        if (scope != null) n.put("scope", scope);
        if (network != null) n.put("network", network);
        if (localFallback) n.put("localFallback", true);
        return n;
    }

    /**
     * Strict read of a settings value.
     *
     * @throws IllegalArgumentException when it is not an object or a field is invalid
     */
    public static RoleSandbox parse(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return NONE;
        if (!n.isObject()) throw new IllegalArgumentException("'sandbox' must be an object {kind, scope, network, localFallback}");
        JsonNode lf = n.path("localFallback");
        if (!lf.isMissingNode() && !lf.isNull() && !lf.isBoolean()) {
            throw new IllegalArgumentException("sandbox localFallback must be true or false");
        }
        return of(n.path("kind").asText(null), n.path("scope").asText(null), n.path("network").asText(null),
                lf.asBoolean(false));
    }

    /** Lenient read of a settings value: absent or invalid is {@link #NONE} (a bad stored value must not stop the server). */
    public static RoleSandbox of(JsonNode n) {
        try {
            return parse(n);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}

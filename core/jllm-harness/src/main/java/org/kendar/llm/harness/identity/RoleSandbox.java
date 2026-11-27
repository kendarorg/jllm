package org.kendar.llm.harness.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.sandbox.SandboxKind;

/**
 * The sandbox an admin gives a role, kept as the {@code sandbox} key of the role settings:
 * {@code {"kind": ..., "scope": ..., "network": ...}}. Null container fields keep the configured defaults.
 *
 * @param kind    none (default), local, docker-isolated (hardened container) or docker-open (standard container)
 * @param scope   docker: {@code chat} (one container per chat) or {@code user} (one per user)
 * @param network docker-isolated: {@code none} or {@code default}; ignored for the other kinds
 */
public record RoleSandbox(SandboxKind kind, String scope, String network) {

    public static final RoleSandbox NONE = new RoleSandbox(SandboxKind.NONE, null, null);

    public RoleSandbox {
        kind = kind == null ? SandboxKind.NONE : kind;
        scope = kind.docker() ? check("scope", scope, "chat", "user") : null;
        network = kind == SandboxKind.DOCKER_ISOLATED ? check("network", network, "none", "default") : null;
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

    public static RoleSandbox of(String kind, String scope, String network) {
        return new RoleSandbox(SandboxKind.parse(kind), scope, network);
    }

    /** The settings value: {@code {"kind": ..., "scope": ..., "network": ...}} (null fields left out). */
    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("kind", kind.wire());
        if (scope != null) n.put("scope", scope);
        if (network != null) n.put("network", network);
        return n;
    }

    /**
     * Strict read of a settings value.
     *
     * @throws IllegalArgumentException when it is not an object or a field is invalid
     */
    public static RoleSandbox parse(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return NONE;
        if (!n.isObject()) throw new IllegalArgumentException("'sandbox' must be an object {kind, scope, network}");
        return of(n.path("kind").asText(null), n.path("scope").asText(null), n.path("network").asText(null));
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

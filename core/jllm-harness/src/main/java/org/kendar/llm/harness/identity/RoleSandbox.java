package org.kendar.llm.harness.identity;

import org.kendar.llm.harness.sandbox.SandboxKind;

/**
 * The sandbox an admin gives a role. Null container fields keep the configured defaults.
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
}

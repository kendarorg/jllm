package org.kendar.llm.harness.identity;

import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.permission.ToolPermission;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.Wildcard;

import java.util.List;
import java.util.Map;

/**
 * A role. The {@code admin} role always has every tool; its approval is the only thing that can be set.
 *
 * @param tools    tool name patterns ({@code *} = any run of characters); ["*"] = all
 * @param readOnly only READ_ONLY tools
 * @param approval {@code off}: no approval prompts; {@code ask} (default): the harness policy
 * @param toolPermissions tool name pattern ({@code *} = any run of characters) to {@code allow} (runs unattended,
 *                        no question ever), {@code ask} or {@code deny}; deny wins over ask over allow, then the
 *                        approval above applies
 */
public record Role(String name, List<String> tools, boolean readOnly, String approval,
                   Map<String, ToolPermission> toolPermissions) {

    /** The administrator role: every tool; only its approval and tool permissions can be changed (default: ask). */
    public static final Role ADMIN = admin("ask", Map.of());

    public static Role admin(String approval, Map<String, ToolPermission> toolPermissions) {
        return new Role(User.ADMIN_ROLE, List.of("*"), false, approval, toolPermissions);
    }

    public Role {
        tools = tools == null ? List.of() : List.copyOf(tools);
        approval = approval == null ? "ask" : approval;
        if (!"ask".equals(approval) && !"off".equals(approval)) {
            throw new IllegalArgumentException("'approval' of role " + name + " must be \"ask\" or \"off\"");
        }
        toolPermissions = toolPermissions == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(toolPermissions));
        toolPermissions.forEach((pattern, p) -> {
            if (pattern == null || pattern.isBlank() || pattern.length() > 200 || p == null) {
                throw new IllegalArgumentException("Invalid tool permission in role " + name + ": " + pattern);
            }
        });
    }

    /** The decision for the tool: deny, then ask, then allow; the first list with a matching pattern wins. */
    public ToolPermission permissionOf(HarnessTool tool) {
        for (ToolPermission kind : new ToolPermission[]{ToolPermission.DENY, ToolPermission.ASK, ToolPermission.ALLOW}) {
            for (var e : toolPermissions.entrySet()) {
                if (e.getValue() == kind && Wildcard.matches(e.getKey(), tool.name())) return kind;
            }
        }
        return null;
    }

    public boolean fixed() {
        return User.ADMIN_ROLE.equals(name);
    }

    /** Whether the role lets the tool through (name patterns and read-only). */
    public boolean allows(HarnessTool tool) {
        return allowsName(tool.name()) && (!readOnly || tool.kind() == ToolKind.READ_ONLY);
    }

    public boolean allowsName(String name) {
        return Wildcard.matchesAny(tools, name);
    }

    public UserPolicy policy() {
        ToolApprovalPolicy approvals = "off".equals(approval) ? tool -> false : null;
        return new UserPolicy(this::allows, approvals, null, null, toolPermissions.isEmpty() ? null : this::permissionOf);
    }
}

package org.kendar.llm.web.commons.users;

import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.Wildcard;

import java.util.List;

/**
 * A role.
 *
 * @param tools    tool name patterns ({@code *} = any run of characters); ["*"] = all
 * @param readOnly only READ_ONLY tools
 * @param approval {@code off}: no approval prompts; {@code ask} (default): the harness policy
 */
public record Role(String name, List<String> tools, boolean readOnly, String approval) {

    /** The fixed administrator role: every tool, no prompts. */
    public static final Role ADMIN = new Role(WebUser.ADMIN_ROLE, List.of("*"), false, "off");

    public Role {
        tools = tools == null ? List.of() : List.copyOf(tools);
        approval = approval == null ? "ask" : approval;
        if (!"ask".equals(approval) && !"off".equals(approval)) {
            throw new IllegalArgumentException("'approval' of role " + name + " must be \"ask\" or \"off\"");
        }
    }

    public boolean fixed() {
        return WebUser.ADMIN_ROLE.equals(name);
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
        return new UserPolicy(this::allows, approvals);
    }
}

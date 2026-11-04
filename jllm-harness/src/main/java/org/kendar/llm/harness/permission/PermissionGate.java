package org.kendar.llm.harness.permission;

import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;

/**
 * Pure policy: never prompts itself. mode × kind → {@link ToolApprovalPolicy} → ASK.
 * In PLAN the file tools are allowed here but confined to the scratch directory by {@code ToolContext.readOnlyWork};
 * the shell runs from the scratch directory. The settings' {@link PermissionRules} are evaluated by the caller
 * before this check.
 */
public class PermissionGate {

    private final ToolApprovalPolicy policy;
    private final PermissionRules rules;

    public PermissionGate(ToolApprovalPolicy policy) {
        this(policy, PermissionRules.none());
    }

    public PermissionGate(ToolApprovalPolicy policy, PermissionRules rules) {
        this.policy = policy;
        this.rules = rules == null ? PermissionRules.none() : rules;
    }

    public PermissionRules rules() {
        return rules;
    }

    public Decision check(PermissionMode mode, HarnessTool tool) {
        if (mode == PermissionMode.AUTO) {
            return Decision.ALLOW;
        }
        if ((mode == PermissionMode.EDIT || mode == PermissionMode.PLAN) && tool.kind() == ToolKind.FILE_EDIT) {
            return Decision.ALLOW;
        }
        return policy.requiresApproval(tool) ? Decision.ASK : Decision.ALLOW;
    }
}

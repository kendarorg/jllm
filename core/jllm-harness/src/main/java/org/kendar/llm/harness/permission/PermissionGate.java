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
    private final boolean alwaysAsk;

    public PermissionGate(ToolApprovalPolicy policy) {
        this(policy, PermissionRules.none());
    }

    public PermissionGate(ToolApprovalPolicy policy, PermissionRules rules) {
        this(policy, rules, false);
    }

    /**
     * @param alwaysAsk role level "always ask": the mode never waives a question, so auto and edit mode ask whatever the
     *                  approval policy says is asked
     */
    public PermissionGate(ToolApprovalPolicy policy, PermissionRules rules, boolean alwaysAsk) {
        this.policy = policy;
        this.rules = rules == null ? PermissionRules.none() : rules;
        this.alwaysAsk = alwaysAsk;
    }

    /** The approval policy this gate applies. */
    public ToolApprovalPolicy policy() {
        return policy;
    }

    public PermissionRules rules() {
        return rules;
    }

    public Decision check(PermissionMode mode, HarnessTool tool) {
        if (alwaysAsk) return policy.requiresApproval(tool) ? Decision.ASK : Decision.ALLOW;
        if (mode == PermissionMode.AUTO) {
            return Decision.ALLOW;
        }
        if ((mode == PermissionMode.EDIT || mode == PermissionMode.PLAN) && tool.kind() == ToolKind.FILE_EDIT) {
            return Decision.ALLOW;
        }
        return policy.requiresApproval(tool) ? Decision.ASK : Decision.ALLOW;
    }
}

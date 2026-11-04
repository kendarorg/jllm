package org.kendar.llm.harness.permission;

import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;

/** Decides which tools need the user's approval in the default permission mode. */
public interface ToolApprovalPolicy {

    boolean requiresApproval(HarnessTool tool);

    /** Called once for every tool when the registry is built. */
    default void registered(HarnessTool tool) {
    }

    /** Everything that is not read-only asks. */
    static ToolApprovalPolicy byKind() {
        return tool -> tool.kind() != ToolKind.READ_ONLY;
    }
}

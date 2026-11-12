package org.kendar.llm.harness.auth;

import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.function.Predicate;

/**
 * What one user may use. The tool filter is applied when the chat's tool set is chosen and again at dispatch;
 * it is not a security boundary on its own, the dispatch check is.
 *
 * @param toolFilter tools the user may use; null = all
 * @param approval   approval policy for this user's calls; null = the harness policy
 */
public record UserPolicy(Predicate<HarnessTool> toolFilter, ToolApprovalPolicy approval) {

    /** Every tool, the harness approval policy: the single-user default. */
    public static final UserPolicy ALL = new UserPolicy(null, null);

    public boolean allows(HarnessTool tool) {
        return toolFilter == null || toolFilter.test(tool);
    }
}

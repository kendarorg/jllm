package org.kendar.llm.harness.auth;

import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.List;
import java.util.function.Predicate;

/**
 * What one user may use. The tool filter is applied when the chat's tool set is chosen and again at dispatch;
 * it is not a security boundary on its own, the dispatch check is.
 *
 * @param toolFilter  tools the user may use; null = all
 * @param approval    approval policy for this user's calls; null = the harness policy
 * @param skills      skills of this user on top of the harness ones (their text is read on each load); a name
 *                    already taken by a harness skill is ignored; null = none
 * @param skillFilter harness skills (by name) the user keeps; null = all
 */
public record UserPolicy(Predicate<HarnessTool> toolFilter, ToolApprovalPolicy approval, List<UserSkill> skills,
                         Predicate<String> skillFilter) {

    /** Every tool, the harness approval policy: the single-user default. */
    public static final UserPolicy ALL = new UserPolicy(null, null);

    /** No tool at all: for a caller who cannot be identified where identification is required. */
    public static final UserPolicy NONE = new UserPolicy(t -> false, null);

    public UserPolicy {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** No skills of the user's own, every harness skill. */
    public UserPolicy(Predicate<HarnessTool> toolFilter, ToolApprovalPolicy approval) {
        this(toolFilter, approval, null, null);
    }

    public boolean allows(HarnessTool tool) {
        return toolFilter == null || toolFilter.test(tool);
    }

    public boolean keepsSkill(String name) {
        return skillFilter == null || skillFilter.test(name);
    }
}

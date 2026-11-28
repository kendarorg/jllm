package org.kendar.llm.harness.auth;

import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.permission.ToolPermission;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
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
 * @param toolPermission the administrator's allow / ask / deny per tool; null or returning null = no decision
 * @param instructions   the instructions of the user's role and of the user, added to the system prompt of their chats
 *                       (read on each use); null = none
 * @param scoped         whether the instruction files found in the work dir join the conversation, see {@link Scoped}
 * @param allowedModes   the permission modes the user may select; null = all. A mode outside it can neither be
 *                       selected nor run in
 * @param unattended     nobody answers this user's permission questions: every one is denied (see the unattended runs
 *                       rule); set per role by an administrator
 * @param alwaysAsk      role level "always ask": the permission mode never waives a question (auto and edit mode
 *                       included)
 */
public record UserPolicy(Predicate<HarnessTool> toolFilter, ToolApprovalPolicy approval, List<UserSkill> skills,
                         Predicate<String> skillFilter, Function<HarnessTool, ToolPermission> toolPermission,
                         Supplier<String> instructions, Scoped scoped, Set<PermissionMode> allowedModes,
                         boolean unattended, boolean alwaysAsk) {

    /** The instruction files of the directories a file tool works in ({@code AGENTS.md} and the like). */
    public enum Scoped {
        /** Never. */
        OFF,
        /** Yes: the work dir is the user's own project (the command line). */
        ON,
        /** Yes, labelled as content of the user: the work dir is a chat directory the user can write. */
        USER_CONTENT
    }

    /** Every tool, the harness approval policy: the single-user default. */
    public static final UserPolicy ALL = new UserPolicy(null, null);

    /** No tool at all: for a caller who cannot be identified where identification is required. */
    public static final UserPolicy NONE = new UserPolicy(t -> false, null);

    public UserPolicy {
        skills = skills == null ? List.of() : List.copyOf(skills);
        scoped = scoped == null ? Scoped.ON : scoped;
        allowedModes = allowedModes == null ? null : Set.copyOf(allowedModes);
    }

    /** No skills of the user's own, every harness skill. */
    public UserPolicy(Predicate<HarnessTool> toolFilter, ToolApprovalPolicy approval) {
        this(toolFilter, approval, null, null, null, null, Scoped.ON, null, false, false);
    }

    /** Whether the user may select (and run in) the mode. */
    public boolean allowsMode(PermissionMode mode) {
        return allowedModes == null || allowedModes.contains(mode);
    }

    /**
     * The mode a run uses: {@code requested} when allowed, else the most permissive allowed mode that asks at least as
     * much: the first of default, plan, edit, auto the user may use ({@link PermissionMode#DEFAULT} when none is
     * listed, which cannot happen for a validated role).
     */
    public PermissionMode clampMode(PermissionMode requested) {
        if (allowsMode(requested)) return requested;
        for (PermissionMode m : new PermissionMode[]{PermissionMode.DEFAULT, PermissionMode.PLAN, PermissionMode.EDIT,
                PermissionMode.AUTO}) {
            if (allowsMode(m)) return m;
        }
        return PermissionMode.DEFAULT;
    }

    /** The administrator's decision for the tool, or null when none applies (the usual approval flow decides). */
    public ToolPermission permissionOf(HarnessTool tool) {
        return toolPermission == null ? null : toolPermission.apply(tool);
    }

    public boolean allows(HarnessTool tool) {
        return toolFilter == null || toolFilter.test(tool);
    }

    /** The instructions of the user's role and of the user; empty when there are none. */
    public String instructionsText() {
        String t = instructions == null ? null : instructions.get();
        return t == null ? "" : t;
    }

    public boolean keepsSkill(String name) {
        return skillFilter == null || skillFilter.test(name);
    }
}

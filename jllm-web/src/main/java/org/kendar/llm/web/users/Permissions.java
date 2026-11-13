package org.kendar.llm.web.users;

import org.kendar.llm.web.commons.users.WebUser;
import org.kendar.llm.web.commons.users.Role;
import org.kendar.llm.web.commons.users.UserRepository;
import org.kendar.llm.web.commons.users.RoleRepository;
import org.kendar.llm.web.commons.users.SkillRepository;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.harness.auth.UserSkill;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.util.Wildcard;

import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The roles and what they let a user do. The roles an admin defines are kept by a {@link RoleRepository}; a role
 * without a valid record gets no tools. The {@code admin} role is fixed: every tool, no prompts, whatever is stored
 * under its name. A user narrows their role with {@code disabledTools}; their skills (global and own, minus
 * {@code disabledSkills}) travel in the policy.
 */
public final class Permissions {

    public static final Pattern ROLE = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final UserPolicy NOTHING = UserPolicy.NONE;

    private final RoleRepository roles;

    public Permissions(RoleRepository roles) {
        this.roles = roles;
    }

    public static boolean validName(String role) {
        return role != null && ROLE.matcher(role).matches();
    }

    /** The role, or empty when it has no valid record. {@code admin} always exists. */
    public Optional<Role> role(String name) {
        if (WebUser.ADMIN_ROLE.equals(name)) return Optional.of(Role.ADMIN);
        if (!validName(name)) return Optional.empty();
        return roles.find(name);
    }

    /** Every valid role, {@code admin} first. */
    public List<Role> roles() {
        List<Role> out = new ArrayList<>();
        out.add(Role.ADMIN);
        roles.all().stream().filter(r -> validName(r.name()) && !r.fixed()).forEach(out::add);
        return out;
    }

    /** Stores a role. @throws IllegalArgumentException for {@code admin} or an invalid name */
    public Role save(Role role) {
        if (role.fixed()) throw new IllegalArgumentException("The admin role cannot be changed");
        if (!validName(role.name())) throw new IllegalArgumentException("Invalid role name: " + role.name());
        roles.save(role);
        return role(role.name()).orElseThrow();
    }

    public boolean delete(String name) {
        if (WebUser.ADMIN_ROLE.equals(name)) throw new IllegalArgumentException("The admin role cannot be deleted");
        return validName(name) && roles.delete(name);
    }

    /** The policy of a role, or empty when the role has no valid record. */
    public Optional<UserPolicy> forRole(String role) {
        return role(role).map(Role::policy);
    }

    /**
     * Resolves the policy of the chat's user from their role, narrowed by their {@code disabledTools}. A chat without
     * a principal (ownerless, e.g. created outside the web API) gets no tools: on the web there is no single-user
     * default. Without a user repository (tests), the role alone decides.
     */
    public UserPolicyResolver resolver() {
        return resolver(null, null);
    }

    public UserPolicyResolver resolver(UserRepository users, SkillRepository skills) {
        return principal -> {
            if (principal == null) return NOTHING;
            Optional<WebUser> user = users == null ? Optional.empty() : users.find(principal.id());
            String roleName = user.map(WebUser::role)
                    .orElseGet(() -> principal.roles().stream().findFirst().orElse(null));
            if (users != null && user.isEmpty()) return NOTHING;
            Optional<Role> role = role(roleName);
            if (role.isEmpty()) return NOTHING;
            if (user.isEmpty()) return role.get().policy();
            return policyOf(role.get(), user.get(), skills);
        };
    }

    /** The role's policy narrowed by the user's settings, with the user's skills (global first, then own). */
    public static UserPolicy policyOf(Role role, WebUser user, SkillRepository skills) {
        List<String> disabledTools = user.disabledTools();
        Set<String> disabledSkills = new HashSet<>(user.disabledSkills());
        Predicate<HarnessTool> filter = t -> role.allows(t) && !Wildcard.matchesAny(disabledTools, t.name());
        ToolApprovalPolicy approvals = "off".equals(role.approval()) ? tool -> false : null;
        List<UserSkill> extra = new ArrayList<>();
        if (skills != null) {
            Set<String> taken = new HashSet<>();
            for (SkillRepository.Skill s : skills.global()) {
                taken.add(s.name());
                if (disabledSkills.contains(s.name())) continue;
                extra.add(new UserSkill(s.name(), s.description(), () -> skills.findGlobal(s.name())
                        .orElseThrow(() -> new NoSuchFileException("global skill " + s.name())).content()));
            }
            for (SkillRepository.Skill s : skills.own(user.id())) {
                if (taken.contains(s.name()) || disabledSkills.contains(s.name())) continue;
                extra.add(new UserSkill(s.name(), s.description(), () -> skills.findOwn(user.id(), s.name())
                        .orElseThrow(() -> new NoSuchFileException("own skill " + s.name())).content()));
            }
        }
        return new UserPolicy(filter, approvals, extra, name -> !disabledSkills.contains(name));
    }
}

package org.kendar.llm.web.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.settings.SkillSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.Wildcard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One file per role under the permissions directory: {@code <role>.json}, read on each use.
 * <pre>
 * {
 *   "tools": ["read_file", "mcp__docs__*"],    tool name patterns ({@code *} = any run of characters); ["*"] = all
 *   "readOnly": true,                          only READ_ONLY tools (optional, default false)
 *   "approval": "off"                          no approval prompts (optional; default "ask" = the harness policy)
 * }
 * </pre>
 * A role without a valid file gets no tools. The {@code admin} role is fixed: every tool, no prompts, whatever its
 * file says. A user narrows their role with {@code disabledTools}; their skills (global and own, minus
 * {@code disabledSkills}) travel in the policy.
 */
public final class Permissions {

    private static final Logger log = LoggerFactory.getLogger(Permissions.class);
    public static final Pattern ROLE = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final UserPolicy NOTHING = UserPolicy.NONE;

    /** A role file. */
    public record Role(String name, List<String> tools, boolean readOnly, String approval) {
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

    private static final Role ADMIN = new Role(WebUser.ADMIN_ROLE, List.of("*"), false, "off");

    private final Path dir;
    private final ObjectMapper mapper;

    public Permissions(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    public static boolean validName(String role) {
        return role != null && ROLE.matcher(role).matches();
    }

    /** The role, or empty when it has no valid file. {@code admin} always exists. */
    public Optional<Role> role(String name) {
        if (WebUser.ADMIN_ROLE.equals(name)) return Optional.of(ADMIN);
        if (!validName(name)) return Optional.empty();
        Path file = dir.resolve(name + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(parse(name, mapper.readTree(file.toFile())));
        } catch (IOException | RuntimeException e) {
            log.warn("Permission file {} skipped: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Every valid role, {@code admin} first. */
    public List<Role> roles() {
        List<Role> out = new ArrayList<>();
        out.add(ADMIN);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .filter(n -> validName(n) && !WebUser.ADMIN_ROLE.equals(n))
                    .sorted()
                    .map(this::role)
                    .flatMap(Optional::stream)
                    .forEach(out::add);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    /** Writes a role file. @throws IllegalArgumentException for {@code admin} or an invalid name */
    public Role save(Role role) {
        if (role.fixed()) throw new IllegalArgumentException("The admin role cannot be changed");
        if (!validName(role.name())) throw new IllegalArgumentException("Invalid role name: " + role.name());
        JsonFiles.update(mapper, dir.resolve(role.name() + ".json"), n -> {
            ArrayNode tools = n.putArray("tools");
            role.tools().forEach(tools::add);
            n.put("readOnly", role.readOnly());
            n.put("approval", role.approval());
        });
        return role(role.name()).orElseThrow();
    }

    public boolean delete(String name) {
        if (WebUser.ADMIN_ROLE.equals(name)) throw new IllegalArgumentException("The admin role cannot be deleted");
        return validName(name) && JsonFiles.delete(dir.resolve(name + ".json"));
    }

    /** The policy of a role, or empty when the role has no valid file. */
    public Optional<UserPolicy> forRole(String role) {
        return role(role).map(Role::policy);
    }

    /**
     * Resolves the policy of the chat's user from their role, narrowed by their {@code disabledTools}. A chat without
     * a principal (ownerless, e.g. created outside the web API) gets no tools: on the web there is no single-user
     * default. Without a user directory (tests), the role alone decides.
     */
    public UserPolicyResolver resolver() {
        return resolver(null, null);
    }

    public UserPolicyResolver resolver(UserDirectory users, SkillFiles skills) {
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

    /** The role's policy narrowed by the user's settings, with the user's skills. */
    public static UserPolicy policyOf(Role role, WebUser user, SkillFiles skills) {
        List<String> disabledTools = user.disabledTools();
        Set<String> disabledSkills = new HashSet<>(user.disabledSkills());
        Predicate<HarnessTool> filter = t -> role.allows(t) && !Wildcard.matchesAny(disabledTools, t.name());
        ToolApprovalPolicy approvals = "off".equals(role.approval()) ? tool -> false : null;
        List<SkillSettings> extra = new ArrayList<>();
        if (skills != null) {
            Set<String> taken = new HashSet<>();
            for (SkillFiles.Skill s : skills.global()) {
                taken.add(s.name());
                if (!disabledSkills.contains(s.name())) extra.add(settings(s));
            }
            for (SkillFiles.Skill s : skills.own(user.id())) {
                if (!taken.contains(s.name()) && !disabledSkills.contains(s.name())) extra.add(settings(s));
            }
        }
        return new UserPolicy(filter, approvals, extra, name -> !disabledSkills.contains(name));
    }

    private static SkillSettings settings(SkillFiles.Skill s) {
        return new SkillSettings(s.name(), s.description(), s.path().toString());
    }

    private static List<String> names(JsonNode tools) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : tools) out.add(t.asText());
        return out;
    }

    private static Role parse(String role, JsonNode n) {
        JsonNode tools = n.path("tools");
        if (!tools.isArray()) {
            throw new IllegalArgumentException("'tools' of role " + role + " must be an array");
        }
        return new Role(role, names(tools), n.path("readOnly").asBoolean(false), n.path("approval").asText("ask"));
    }
}

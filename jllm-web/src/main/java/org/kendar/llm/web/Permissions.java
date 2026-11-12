package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * One file per role under the permissions directory: {@code <role>.json}, read on each use.
 * <pre>
 * {
 *   "tools": ["read_file", "grep"],            tool names allowed to the role; ["*"] = every tool
 *   "readOnly": true,                          only READ_ONLY tools (optional, default false)
 *   "approval": "off"                          no approval prompts (optional; default "ask" = the harness policy)
 * }
 * </pre>
 * A role without a valid file gets no tools.
 */
public final class Permissions {

    private static final Logger log = LoggerFactory.getLogger(Permissions.class);
    private static final Pattern ROLE = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final UserPolicy NOTHING = new UserPolicy(t -> false, null);

    private final Path dir;
    private final ObjectMapper mapper;

    public Permissions(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    /** The policy of a role, or empty when the role has no valid file. */
    public Optional<UserPolicy> forRole(String role) {
        if (role == null || !ROLE.matcher(role).matches()) return Optional.empty();
        Path file = dir.resolve(role + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(parse(role, mapper.readTree(file.toFile())));
        } catch (IOException | RuntimeException e) {
            log.warn("Permission file {} skipped: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Resolves the policy of the chat's user from the file of their role. */
    public UserPolicyResolver resolver() {
        return user -> forRole(user.roles().stream().findFirst().orElse(null)).orElse(NOTHING);
    }

    private static Set<String> names(JsonNode tools) {
        Set<String> out = new HashSet<>();
        for (JsonNode t : tools) out.add(t.asText());
        return out;
    }

    private static UserPolicy parse(String role, JsonNode n) {
        JsonNode tools = n.path("tools");
        if (!tools.isArray()) {
            throw new IllegalArgumentException("'tools' of role " + role + " must be an array");
        }
        Set<String> names = names(tools);
        Set<String> allowed = names.contains("*") ? null : names;
        boolean readOnly = n.path("readOnly").asBoolean(false);
        String approval = n.path("approval").asText("ask");
        if (!"ask".equals(approval) && !"off".equals(approval)) {
            throw new IllegalArgumentException("'approval' of role " + role + " must be \"ask\" or \"off\"");
        }
        Predicate<HarnessTool> filter = t -> (allowed == null || allowed.contains(t.name()))
                && (!readOnly || t.kind() == ToolKind.READ_ONLY);
        ToolApprovalPolicy approvals = "off".equals(approval) ? tool -> false : null;
        return new UserPolicy(filter, approvals);
    }
}

package org.kendar.llm.web.users;

import org.kendar.llm.web.commons.users.Permissions;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.RoleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One file per role under the permissions directory: {@code <role>.json}, read on each use.
 * <pre>
 * {
 *   "tools": ["read_file", "mcp__docs__*"],    tool name patterns ({@code *} = any run of characters); ["*"] = all
 *   "readOnly": true,                          only READ_ONLY tools (optional, default false)
 *   "approval": "off",                         no approval prompts (optional; default "ask" = the harness policy)
 *   "toolPermissions": {"send_mail": "allow",  per tool pattern: allow = runs unattended, never asked;
 *                       "bash": "deny"},       ask = always asked; deny = never runs (deny, ask, allow order)
 *   "sandbox": {"kind": "docker-isolated",     none (default: no files, no shell), shared-local, docker-isolated
 *                                              (hardened container) or docker-open (standard container)
 *               "scope": "user",               docker: chat or user (optional)
 *               "network": "none"},            docker-isolated, shared-local: none or default (optional)
 *   "idp": {...}                               every other key is a role setting (free JSON per feature)
 * }
 * </pre>
 * A file for {@code admin} is read for its {@code approval}, {@code toolPermissions} and settings; its {@code tools} and
 * {@code readOnly} are ignored: that role always has every tool ({@link Permissions}).
 */
public final class FileRoleRepository implements RoleRepository {

    private static final Logger log = LoggerFactory.getLogger(FileRoleRepository.class);
    /** The role's own fields; every other top-level key is a setting ({@link Role#settings()}). */
    private static final java.util.Set<String> FIELDS = java.util.Set.of("tools", "readOnly", "approval", "toolPermissions");
    private static final java.util.regex.Pattern SETTING_KEY = java.util.regex.Pattern.compile("[a-z0-9_-]{1,64}");

    private final Path dir;
    private final ObjectMapper mapper;

    public FileRoleRepository(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    @Override
    public Optional<Role> find(String name) {
        if (!Permissions.validName(name)) return Optional.empty();
        Path file = dir.resolve(name + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(parse(name, mapper.readTree(file.toFile())));
        } catch (IOException | IllegalArgumentException e) {
            log.debug("Stack of the skipped permission file {}", file, e);
            log.warn("Permission file {} skipped: {}", file, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<Role> all() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void save(Role role) {
        JsonFiles.update(mapper, dir.resolve(role.name() + ".json"), n -> {
            // the settings map is the whole truth: drop the stored ones first (a key the role no longer has goes)
            List<String> old = new ArrayList<>();
            n.fieldNames().forEachRemaining(k -> {
                if (!FIELDS.contains(k) && SETTING_KEY.matcher(k).matches()) old.add(k);
            });
            old.forEach(n::remove);
            ArrayNode tools = n.putArray("tools");
            role.tools().forEach(tools::add);
            n.put("readOnly", role.readOnly());
            n.put("approval", role.approval());
            n.remove("toolPermissions");
            if (!role.toolPermissions().isEmpty()) {
                var perms = n.putObject("toolPermissions");
                role.toolPermissions().forEach((pattern, p) -> perms.put(pattern, p.name().toLowerCase()));
            }
            role.settings().forEach(n::set);
        });
    }

    @Override
    public boolean delete(String name) {
        return Permissions.validName(name) && JsonFiles.delete(dir.resolve(name + ".json"));
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
        java.util.Map<String, org.kendar.llm.harness.permission.ToolPermission> perms = new java.util.LinkedHashMap<>();
        JsonNode p = n.path("toolPermissions");
        if (!p.isMissingNode() && !p.isNull() && !p.isObject()) {
            throw new IllegalArgumentException("'toolPermissions' of role " + role + " must be an object");
        }
        p.fields().forEachRemaining(e -> perms.put(e.getKey(),
                org.kendar.llm.harness.permission.ToolPermission.parse(e.getValue().asText())));
        java.util.Map<String, JsonNode> settings = new java.util.LinkedHashMap<>();
        n.fields().forEachRemaining(e -> {
            if (FIELDS.contains(e.getKey())) return;
            if (!Role.settingKey(e.getKey())) {
                log.warn("Key '{}' of role {} ignored: not a valid settings key", e.getKey(), role);
                return;
            }
            settings.put(e.getKey(), e.getValue());
        });
        try {
            return new Role(role, names(tools), n.path("readOnly").asBoolean(false), n.path("approval").asText("ask"),
                    perms, settings);
        } catch (IllegalArgumentException e) {
            if (settings.isEmpty()) throw e;
            // settings over the caps: keep the role, drop them (the file must not stop the server)
            log.warn("Settings of role {} ignored: {}", role, e.getMessage());
            return new Role(role, names(tools), n.path("readOnly").asBoolean(false), n.path("approval").asText("ask"),
                    perms, java.util.Map.of());
        }
    }
}

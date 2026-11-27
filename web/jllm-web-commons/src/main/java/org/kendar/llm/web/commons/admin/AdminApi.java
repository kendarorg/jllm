package org.kendar.llm.web.commons.admin;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.web.commons.Api;
import org.kendar.llm.web.commons.ApiException;
import org.kendar.llm.web.commons.Reply;
import org.kendar.llm.web.commons.auth.AuthApi;
import org.kendar.llm.web.commons.auth.IdpSetting;
import org.kendar.llm.web.commons.auth.LoginThrottle;
import org.kendar.llm.web.commons.auth.PasswordHasher;
import org.kendar.llm.web.commons.auth.RoleMapper;
import org.kendar.llm.web.commons.auth.RoleMappingOptions;
import org.kendar.llm.web.commons.auth.RoleMappingSettings;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.me.MeApi;
import org.kendar.llm.web.commons.users.Permissions;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.RoleSandbox;
import org.kendar.llm.harness.identity.RoleInUseException;
import org.kendar.llm.harness.identity.UnknownRoleException;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.identity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Administration of users, roles and global skills, plus the catalog of everything a role can be granted. Only
 * admins get here ({@link org.kendar.llm.web.commons.Gate#decideAdmin}). The {@code admin} role is fixed; at least one admin user remains; an
 * admin cannot delete their own account.
 */
public class AdminApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>> OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    private final UserRepository users;
    private final Permissions permissions;
    private final SkillRepository skills;
    private final LoginSessionRepository sessions;
    private final AgentHarness harness;
    private final ToolCatalogView catalog;
    private final RoleMappingSettings roleMapping;
    private final LoginThrottle throttle;

    /**
     * @param roleMapping the options of the identity provider role mapping
     * @param throttle    the sign-in throttle, whose locks an administrator can lift
     */
    public AdminApi(UserRepository users, Permissions permissions, SkillRepository skills, LoginSessionRepository sessions,
                    AgentHarness harness, ToolCatalogView catalog, RoleMappingSettings roleMapping,
                    LoginThrottle throttle) {
        this.throttle = throttle;
        this.users = users;
        this.permissions = permissions;
        this.skills = skills;
        this.sessions = sessions;
        this.harness = harness;
        this.catalog = catalog;
        this.roleMapping = roleMapping;
    }

    // ---- users

    /**
     * {@code roleManagedByIdp}: the role mapping is enabled and the user is linked to the identity provider, so the next
     * sign-in through the provider sets the role from the groups (overwriting one set here), password or not.
     */
    private Map<String, Object> row(User u) {
        Map<String, Object> m = new LinkedHashMap<>(AuthApi.visible(u));
        m.put("roleManagedByIdp", u.external() && roleMapping.current().enabled());
        m.put("ownSkills", skills.own(u.id()).size());
        m.put("disabledTools", u.disabledTools());
        m.put("disabledSkills", u.disabledSkills());
        m.put("lockedFor", throttle.lockedForUser(u.id())); // seconds; 0 = not locked
        return m;
    }

    /** Whether another user than {@code except} has this email (ignoring case); the sign-in links by it. */
    private boolean emailTaken(String email, String except) {
        return users.findByEmail(email).stream().anyMatch(u -> !u.id().equals(except));
    }

    private long admins() {
        return users.all().stream().filter(User::admin).count();
    }

    public List<Map<String, Object>> userList() {
        return users.all().stream().map(this::row).toList();
    }

    /**
     * Creates a user. With a {@code password} it is the user's password; without one a random one-time password is
     * generated, returned once as {@code temporaryPassword}, and the user must change it at the first sign-in.
     */
    public Reply<Map<String, Object>> createUser(Map<String, Object> body) {
        String id = Api.text(body, "id");
        String role = Api.text(body, "role");
        String password = Api.text(body, "password");
        String email = Api.text(body, "email");
        if (!UserRepository.validId(id)) throw new ApiException(400, "Login: 1-64 of a-z, 0-9, '_' and '-'");
        if (permissions.role(role).isEmpty()) throw new ApiException(400, "Unknown role " + role);
        if (users.exists(id)) throw new ApiException(409, "User " + id + " exists");
        if (emailTaken(email, null)) throw new ApiException(409, "Email " + email + " belongs to another user");
        String hash;
        String generated = null;
        if (password != null && !password.isEmpty()) {
            String invalid = MeApi.invalidPassword(id, password);
            if (invalid != null) throw new ApiException(400, invalid);
            hash = PasswordHasher.hash(password);
        } else {
            generated = PasswordHasher.generate();
            hash = PasswordHasher.hashTemporary(generated);
        }
        try {
            Map<String, Object> out = row(users.create(id, Api.text(body, "name"), role, hash, email));
            if (generated != null) out.put("temporaryPassword", generated);
            return Reply.status(201, out);
        } catch (UnknownRoleException e) {
            throw new ApiException(400, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
    }

    public Map<String, Object> updateUser(String id, Map<String, Object> body) {
        Optional<User> found = users.find(id);
        if (found.isEmpty()) throw new ApiException(404, "Unknown user " + id);
        String name = Api.text(body, "name");
        String role = Api.text(body, "role");
        if (role != null) {
            if (permissions.role(role).isEmpty()) throw new ApiException(400, "Unknown role " + role);
            if (found.get().admin() && !User.ADMIN_ROLE.equals(role) && admins() <= 1) {
                throw new ApiException(409, "The last admin cannot lose the admin role");
            }
        }
        User updated = found.get();
        if (body != null && body.containsKey("email")) {
            String email = Api.text(body, "email");
            if (emailTaken(email, id)) throw new ApiException(409, "Email " + email + " belongs to another user");
            updated = users.setEmail(id, email);
        }
        if (name != null && !name.isBlank()) updated = users.setName(id, name);
        if (role != null) {
            try {
                updated = users.setRole(id, role);
            } catch (UnknownRoleException e) {
                throw new ApiException(400, e.getMessage());
            }
        }
        return row(updated);
    }

    /**
     * Sets a new password, or generates a one-time one when none is given (returned once as
     * {@code temporaryPassword}; the user must change it at the first sign-in). Signs the user out everywhere.
     */
    public Map<String, Object> setPassword(String id, Map<String, Object> body) {
        Optional<User> target = users.find(id);
        if (target.isEmpty()) throw new ApiException(404, "Unknown user " + id);
        if (target.get().passwordless()) {
            throw new ApiException(409, "This account signs in through the identity provider");
        }
        String password = Api.text(body, "password");
        Map<String, Object> out = new LinkedHashMap<>();
        String hash;
        if (password != null && !password.isEmpty()) {
            String invalid = MeApi.invalidPassword(id, password);
            if (invalid != null) throw new ApiException(400, invalid);
            hash = PasswordHasher.hash(password);
        } else {
            String generated = PasswordHasher.generate();
            hash = PasswordHasher.hashTemporary(generated);
            out.put("temporaryPassword", generated);
        }
        users.setPassword(id, hash);
        sessions.revokeUser(id, null);
        throttle.resetUser(id);
        return out;
    }

    /** {@code POST /api/admin/users/{id}/unlock}: lifts the sign-in lock of a login (the 30 minutes after 3 failures). */
    public Map<String, Object> unlockUser(String id) {
        if (users.find(id).isEmpty()) throw new ApiException(404, "Unknown user " + id);
        boolean wasLocked = throttle.lockedForUser(id) > 0;
        throttle.resetUser(id);
        return Map.of("id", id, "wasLocked", wasLocked);
    }

    public void deleteUser(String id,
                                             Principal me) {
        Optional<User> found = users.find(id);
        if (found.isEmpty()) throw new ApiException(404, "Unknown user " + id);
        if (me != null && me.id().equals(id)) throw new ApiException(409, "You cannot delete your own account");
        if (found.get().admin() && admins() <= 1) throw new ApiException(409, "The last admin cannot be deleted");
        users.delete(id);
        skills.deleteAllOwn(id);
        permissions.instructionRepository().deleteOwned(org.kendar.llm.harness.settings.InstructionRepository.Scope.USER, id);
        sessions.revokeUser(id, null);
        harness.auth().forgetUser(id); // the user's OAuth tokens and MCP sessions
    }

    // ---- roles

    private Map<String, Object> row(Role r, List<User> all) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name());
        m.put("tools", r.tools());
        m.put("readOnly", r.readOnly());
        m.put("approval", r.approval());
        Map<String, String> perms = new LinkedHashMap<>();
        r.toolPermissions().forEach((pattern, p) -> perms.put(pattern, p.name().toLowerCase()));
        m.put("toolPermissions", perms);
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("kind", r.sandbox().kind().wire());
        sb.put("scope", r.sandbox().scope());
        sb.put("network", r.sandbox().network());
        sb.put("localFallback", r.sandbox().localFallback());
        m.put("sandbox", sb);
        m.put("settings", settingsView(r));
        m.put("fixed", r.fixed());
        m.put("users", all.stream().filter(u -> r.name().equals(u.role())).count());
        return m;
    }

    public List<Map<String, Object>> roleList() {
        List<User> all = users.all();
        return permissions.roles().stream().map(r -> row(r, all)).toList();
    }

    public Reply<Map<String, Object>> createRole(Map<String, Object> body) {
        String name = Api.text(body, "name");
        if (User.ADMIN_ROLE.equals(name)) throw new ApiException(409, "The admin role is fixed");
        if (!Permissions.validName(name)) throw new ApiException(400, "Role: 1-64 of a-z, 0-9, '_' and '-'");
        if (permissions.role(name).isPresent()) throw new ApiException(409, "Role " + name + " exists");
        return Reply.status(201, saveRole(name, body));
    }

    public Map<String, Object> updateRole(String name, Map<String, Object> body) {
        if (User.ADMIN_ROLE.equals(name)) { // every tool stays; only the approval changes
            try {
                return row(permissions.save(Role.admin(Api.text(body, "approval"), toolPermissions(body),
                        settings(body, permissions.role(name).map(Role::settings).orElse(Map.of())))), users.all());
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, e.getMessage());
            }
        }
        if (permissions.role(name).isEmpty()) throw new ApiException(404, "Unknown role " + name);
        return saveRole(name, body);
    }

    private Map<String, Object> saveRole(String name, Map<String, Object> body) {
        List<String> tools = strings(body, "tools");
        if (tools == null) throw new ApiException(400, "'tools' must be an array of strings");
        if (tools.stream().anyMatch(t -> t.isBlank() || t.length() > 200)) {
            throw new ApiException(400, "Invalid tool entry");
        }
        boolean readOnly = body != null && Boolean.TRUE.equals(body.get("readOnly"));
        String approval = Api.text(body, "approval");
        try {
            Role saved = permissions.save(new Role(name, tools.stream().distinct().toList(),
                    readOnly, approval, toolPermissions(body),
                    settings(body, permissions.role(name).map(Role::settings).orElse(Map.of()))));
            return row(saved, users.all());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /**
     * The settings of a saved role. {@code settings}: the whole map (object of feature to JSON), absent keeps
     * {@code current}; {@code sandbox}: {@code {kind: none|shared-local|docker-isolated|docker-open, scope, network, localFallback}}, absent
     * keeps the sandbox of the map (a client that does not know a field must not take it away). Each known feature is
     * validated (400).
     */
    private static Map<String, JsonNode> settings(Map<String, Object> body, Map<String, JsonNode> current) {
        Map<String, JsonNode> out = new LinkedHashMap<>(current);
        Object raw = body == null ? null : body.get("settings");
        if (raw != null) {
            if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("'settings' must be an object of feature to JSON");
            out.clear();
            m.forEach((k, v) -> out.put(String.valueOf(k), MAPPER.valueToTree(v)));
        }
        Object sb = body == null ? null : body.get("sandbox");
        if (sb != null) {
            if (!(sb instanceof Map<?, ?>)) throw new IllegalArgumentException("'sandbox' must be an object {kind, scope, network, localFallback}");
            out.put(Role.SANDBOX, MAPPER.valueToTree(sb));
        }
        validate(out);
        return out;
    }

    /** Strict checks of the features this server knows; the stored form of the sandbox is its normalized one. */
    private static void validate(Map<String, JsonNode> settings) {
        JsonNode sb = settings.get(Role.SANDBOX);
        if (sb != null && !sb.isNull()) settings.put(Role.SANDBOX, RoleSandbox.parse(sb).toJson());
        JsonNode modes = settings.get(Role.ALLOWED_MODES);
        if (modes != null && !modes.isNull()) settings.put(Role.ALLOWED_MODES, Role.modesJson(Role.modes(modes)));
        JsonNode unattended = settings.get(Role.UNATTENDED);
        if (unattended != null && !unattended.isNull() && !unattended.isBoolean()) {
            throw new IllegalArgumentException("'" + Role.UNATTENDED + "' must be true or false");
        }
        JsonNode idp = settings.get(IdpSetting.KEY);
        if (idp != null && !idp.isNull()) settings.put(IdpSetting.KEY, IdpSetting.parse(idp).toJson());
    }

    private static Map<String, Object> settingsView(Role r) {
        Map<String, Object> out = new LinkedHashMap<>();
        r.settings().forEach((k, v) -> out.put(k, MAPPER.convertValue(v, Object.class)));
        return out;
    }

    /** {@code GET /api/admin/roles/{name}/settings}: the settings map of a role. */
    public Map<String, Object> roleSettings(String name) {
        return settingsView(permissions.role(name).orElseThrow(() -> new ApiException(404, "Unknown role " + name)));
    }

    /** {@code PUT /api/admin/roles/{name}/settings/{feature}}: replaces one feature's settings. */
    public Map<String, Object> putRoleSetting(String name, String feature, Object value) {
        Role role = permissions.role(name).orElseThrow(() -> new ApiException(404, "Unknown role " + name));
        if (value == null) throw new ApiException(400, "A JSON value is required");
        try {
            Map<String, JsonNode> next = new LinkedHashMap<>(role.settings());
            next.put(feature, MAPPER.valueToTree(value));
            validate(next);
            return settingsView(permissions.save(new Role(role.name(), role.tools(), role.readOnly(), role.approval(),
                    role.toolPermissions(), next)));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** {@code DELETE /api/admin/roles/{name}/settings/{feature}}: removes one feature's settings. */
    public Map<String, Object> deleteRoleSetting(String name, String feature) {
        Role role = permissions.role(name).orElseThrow(() -> new ApiException(404, "Unknown role " + name));
        if (role.setting(feature) == null) throw new ApiException(404, "Role " + name + " has no settings '" + feature + "'");
        return settingsView(permissions.save(role.withSetting(feature, null)));
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }

    /** {@code toolPermissions}: object of tool pattern to allow / ask / deny; absent = none. */
    private static Map<String, org.kendar.llm.harness.permission.ToolPermission> toolPermissions(
            Map<String, Object> body) {
        Object raw = body == null ? null : body.get("toolPermissions");
        Map<String, org.kendar.llm.harness.permission.ToolPermission> out = new LinkedHashMap<>();
        if (raw == null) return out;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("'toolPermissions' must be an object of tool pattern to allow, ask or deny");
        }
        m.forEach((k, v) -> out.put(String.valueOf(k),
                org.kendar.llm.harness.permission.ToolPermission.parse(v == null ? null : v.toString())));
        return out;
    }

    public void deleteRole(String name) {
        if (User.ADMIN_ROLE.equals(name)) throw new ApiException(403, "The admin role is fixed");
        if (permissions.role(name).isEmpty()) throw new ApiException(404, "Unknown role " + name);
        // the same answer for every store: the file one cannot refuse by itself, the SQL one also has a foreign key
        List<String> holders = users.all().stream().filter(u -> name.equals(u.role())).map(User::id).toList();
        if (!holders.isEmpty()) throw new ApiException(409, new RoleInUseException(name, holders).getMessage());
        try {
            permissions.delete(name);
            permissions.instructionRepository().deleteOwned(org.kendar.llm.harness.settings.InstructionRepository.Scope.ROLE, name);
        } catch (RoleInUseException e) { // a user got the role meanwhile
            throw new ApiException(409, e.getMessage());
        }
    }

    // ---- identity provider role mapping

    /**
     * {@code GET /api/admin/auth/role-mapping}: the options in force, whether they are stored (else the properties
     * apply), whether they can be edited, the default role and the values of the properties.
     */
    public Map<String, Object> roleMapping() {
        Map<String, Object> out = MAPPER.convertValue(roleMapping.current().toJson(), OBJECT);
        out.put("stored", roleMapping.stored());
        out.put("editable", roleMapping.editable());
        out.put("defaultRole", roleMapping.defaultRole());
        out.put("properties", MAPPER.convertValue(roleMapping.initial().toJson(), OBJECT));
        return out;
    }

    /** {@code PUT /api/admin/auth/role-mapping}: stores the options; an absent key keeps its value (400 on a bad one). */
    public Map<String, Object> putRoleMapping(Map<String, Object> body) {
        if (!roleMapping.editable()) throw new ApiException(409, "The role mapping options are read-only here");
        try {
            roleMapping.put(MAPPER.valueToTree(body == null ? Map.of() : body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return roleMapping();
    }

    /** {@code DELETE /api/admin/auth/role-mapping}: drops the stored options, so the properties apply again. */
    public Map<String, Object> resetRoleMapping() {
        if (!roleMapping.editable()) throw new ApiException(409, "The role mapping options are read-only here");
        roleMapping.reset();
        return roleMapping();
    }

    /**
     * {@code POST /api/admin/auth/role-mapping/preview}: the role a sign-in with {@code groups} (array of strings, or a
     * space or comma separated string) would get, and why. A dry run: it works whether the mapping is enabled or not.
     */
    public Map<String, Object> previewRoleMapping(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("groups");
        List<String> groups = new ArrayList<>();
        if (raw instanceof String s) {
            for (String g : s.split("[\\s,]+")) if (!g.isBlank()) groups.add(g.strip());
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof String s)) throw new ApiException(400, "'groups' must be strings");
                if (!s.isBlank()) groups.add(s.strip());
            }
        } else {
            throw new ApiException(400, "'groups' must be an array of strings or a string");
        }
        groups = groups.stream().distinct().toList();
        if (groups.size() > org.kendar.llm.web.commons.auth.OidcClient.MAX_GROUPS) {
            throw new ApiException(400, "At most " + org.kendar.llm.web.commons.auth.OidcClient.MAX_GROUPS + " groups");
        }
        RoleMappingOptions options = roleMapping.current();
        RoleMapper.Decision d = RoleMapper.decide(options, permissions.roles(), groups);
        return RoleMapper.view(options, roleMapping.defaultRole(), groups, d);
    }

    // ---- catalog

    public ToolCatalogView.Catalog catalog() {
        return catalog.all();
    }

    // ---- global skills

    public List<Map<String, Object>> skillList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillCatalog.Skill s : harness.skills().all()) {
            out.add(Map.of("name", s.name(), "description", s.description(), "source", "settings",
                    "shadows", List.of()));
        }
        for (SkillRepository.Skill s : skills.global()) {
            out.add(Map.of("name", s.name(), "description", s.description(), "source", "global",
                    "shadows", skills.ownersOf(s.name())));
        }
        return out;
    }

    public Map<String, Object> skill(String name) {
        Optional<SkillRepository.Skill> g = skills.findGlobal(name);
        if (g.isPresent()) {
            SkillRepository.Skill s = g.get();
            return Map.of("name", s.name(), "description", s.description(), "source", "global",
                    "content", s.content());
        }
        for (SkillCatalog.Skill s : harness.skills().all()) {
            if (!s.name().equals(name)) continue;
            try {
                return Map.of("name", s.name(), "description", s.description(),
                        "source", "settings", "content", s.text().read());
            } catch (java.io.IOException e) {
                throw new ApiException(404, "Skill file unreadable: " + name);
            }
        }
        throw new ApiException(404, "Unknown skill " + name);
    }

    private boolean settingsSkill(String name) {
        return harness.skills().all().stream().anyMatch(s -> s.name().equals(name));
    }

    public Reply<Map<String, Object>> createSkill(Map<String, Object> body) {
        String name = Api.text(body, "name");
        if (!SkillRepository.validName(name)) throw new ApiException(400, "Skill name: 1-64 of a-z, 0-9, '_' and '-'");
        if (settingsSkill(name) || skills.findGlobal(name).isPresent()) {
            throw new ApiException(409, "A skill named " + name + " exists");
        }
        return Reply.status(201, writeSkill(name, body));
    }

    public Map<String, Object> updateSkill(String name, Map<String, Object> body) {
        if (skills.findGlobal(name).isEmpty()) {
            throw settingsSkill(name) ? new ApiException(403, "Skills of the settings file are read-only")
                    : new ApiException(404, "Unknown skill " + name);
        }
        return writeSkill(name, body);
    }

    private Map<String, Object> writeSkill(String name, Map<String, Object> body) {
        try {
            SkillRepository.Skill s = skills.writeGlobal(name, Api.text(body, "description"), Api.text(body, "content"));
            return Map.of("name", s.name(), "description", s.description(),
                    "source", "global", "content", s.content(), "shadows", skills.ownersOf(name));
        } catch (SkillRepository.SkillTooLargeException e) {
            throw new ApiException(413, e.getMessage());
        }
    }

    public void deleteSkill(String name) {
        if (!skills.deleteGlobal(name)) {
            throw settingsSkill(name) ? new ApiException(403, "Skills of the settings file are read-only")
                    : new ApiException(404, "Unknown skill " + name);
        }
    }

    private static List<String> strings(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s)) return null;
            out.add(s.strip());
        }
        return out.size() > 1000 ? null : out;
    }
}

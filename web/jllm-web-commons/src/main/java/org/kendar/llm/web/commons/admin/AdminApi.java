package org.kendar.llm.web.commons.admin;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.web.commons.Api;
import org.kendar.llm.web.commons.ApiException;
import org.kendar.llm.web.commons.Reply;
import org.kendar.llm.web.commons.auth.AuthApi;
import org.kendar.llm.web.commons.auth.PasswordHasher;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.me.MeApi;
import org.kendar.llm.web.commons.users.Permissions;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.identity.User;
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

    private final UserRepository users;
    private final Permissions permissions;
    private final SkillRepository skills;
    private final LoginSessionRepository sessions;
    private final AgentHarness harness;
    private final ToolCatalogView catalog;

    public AdminApi(UserRepository users, Permissions permissions, SkillRepository skills, LoginSessionRepository sessions,
                           AgentHarness harness, ToolCatalogView catalog) {
        this.users = users;
        this.permissions = permissions;
        this.skills = skills;
        this.sessions = sessions;
        this.harness = harness;
        this.catalog = catalog;
    }

    // ---- users

    private Map<String, Object> row(User u) {
        Map<String, Object> m = new LinkedHashMap<>(AuthApi.visible(u));
        m.put("ownSkills", skills.own(u.id()).size());
        m.put("disabledTools", u.disabledTools());
        m.put("disabledSkills", u.disabledSkills());
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

    public Reply<Map<String, Object>> createUser(Map<String, Object> body) {
        String id = Api.text(body, "id");
        String role = Api.text(body, "role");
        String password = Api.text(body, "password");
        String email = Api.text(body, "email");
        if (!UserRepository.validId(id)) throw new ApiException(400, "Login: 1-64 of a-z, 0-9, '_' and '-'");
        if (permissions.role(role).isEmpty()) throw new ApiException(400, "Unknown role " + role);
        if (users.exists(id)) throw new ApiException(409, "User " + id + " exists");
        if (emailTaken(email, null)) throw new ApiException(409, "Email " + email + " belongs to another user");
        String hash = null;
        if (password != null && !password.isEmpty()) {
            String invalid = MeApi.invalidPassword(id, password);
            if (invalid != null) throw new ApiException(400, invalid);
            hash = PasswordHasher.hash(password);
        }
        try {
            return Reply.status(201, row(users.create(id, Api.text(body, "name"), role, hash, email)));
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
        if (role != null) updated = users.setRole(id, role);
        return row(updated);
    }

    /** Sets a new password, or resets to the default one when none is given; signs the user out everywhere. */
    public void setPassword(String id, Map<String, Object> body) {
        Optional<User> target = users.find(id);
        if (target.isEmpty()) throw new ApiException(404, "Unknown user " + id);
        if (target.get().passwordless()) {
            throw new ApiException(409, "This account signs in through the identity provider");
        }
        String password = Api.text(body, "password");
        String hash = null;
        if (password != null && !password.isEmpty()) {
            String invalid = MeApi.invalidPassword(id, password);
            if (invalid != null) throw new ApiException(400, invalid);
            hash = PasswordHasher.hash(password);
        }
        users.setPassword(id, hash);
        sessions.revokeUser(id, null);
    }

    public void deleteUser(String id,
                                             Principal me) {
        Optional<User> found = users.find(id);
        if (found.isEmpty()) throw new ApiException(404, "Unknown user " + id);
        if (me != null && me.id().equals(id)) throw new ApiException(409, "You cannot delete your own account");
        if (found.get().admin() && admins() <= 1) throw new ApiException(409, "The last admin cannot be deleted");
        users.delete(id);
        skills.deleteAllOwn(id);
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
                return row(permissions.save(Role.admin(Api.text(body, "approval"),
                        toolPermissions(body))), users.all());
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
                    readOnly, approval, toolPermissions(body)));
            return row(saved, users.all());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
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
        long holders = users.all().stream().filter(u -> name.equals(u.role())).count();
        if (holders > 0) throw new ApiException(409, "Role " + name + " is held by " + holders + " user(s)");
        permissions.delete(name);
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

package org.kendar.llm.web.admin;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.web.Api;
import org.kendar.llm.web.auth.AuthController;
import org.kendar.llm.web.auth.PasswordHasher;
import org.kendar.llm.web.auth.SessionStore;
import org.kendar.llm.web.commons.WebPrincipal;
import org.kendar.llm.web.me.MeController;
import org.kendar.llm.web.users.Permissions;
import org.kendar.llm.web.users.SkillFiles;
import org.kendar.llm.web.users.UserDirectory;
import org.kendar.llm.web.users.WebUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Administration of users, roles and global skills, plus the catalog of everything a role can be granted. Only
 * admins get here ({@link AdminInterceptor}). The {@code admin} role is fixed; at least one admin user remains; an
 * admin cannot delete their own account.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserDirectory users;
    private final Permissions permissions;
    private final SkillFiles skills;
    private final SessionStore sessions;
    private final AgentHarness harness;
    private final ToolCatalogView catalog;

    public AdminController(UserDirectory users, Permissions permissions, SkillFiles skills, SessionStore sessions,
                           AgentHarness harness, ToolCatalogView catalog) {
        this.users = users;
        this.permissions = permissions;
        this.skills = skills;
        this.sessions = sessions;
        this.harness = harness;
        this.catalog = catalog;
    }

    // ---- users

    private Map<String, Object> row(WebUser u) {
        Map<String, Object> m = new LinkedHashMap<>(AuthController.visible(u));
        m.put("ownSkills", skills.own(u.id()).size());
        m.put("disabledTools", u.disabledTools());
        m.put("disabledSkills", u.disabledSkills());
        return m;
    }

    private long admins() {
        return users.all().stream().filter(WebUser::admin).count();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> userList() {
        return users.all().stream().map(this::row).toList();
    }

    @PostMapping("/users")
    public ResponseEntity<Object> createUser(@RequestBody(required = false) Map<String, Object> body) {
        String id = Api.text(body, "id");
        String role = Api.text(body, "role");
        String password = Api.text(body, "password");
        if (!UserDirectory.validId(id)) return Api.error(HttpStatus.BAD_REQUEST, "Login: 1-64 of a-z, 0-9, '_' and '-'");
        if (permissions.role(role).isEmpty()) return Api.error(HttpStatus.BAD_REQUEST, "Unknown role " + role);
        if (users.exists(id)) return Api.error(HttpStatus.CONFLICT, "User " + id + " exists");
        String hash = null;
        if (password != null && !password.isEmpty()) {
            String invalid = MeController.invalidPassword(id, password);
            if (invalid != null) return Api.error(HttpStatus.BAD_REQUEST, invalid);
            hash = PasswordHasher.hash(password);
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(row(users.create(id, Api.text(body, "name"), role, hash)));
        } catch (IllegalStateException e) {
            return Api.error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<Object> updateUser(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Optional<WebUser> found = users.find(id);
        if (found.isEmpty()) return Api.error(HttpStatus.NOT_FOUND, "Unknown user " + id);
        String name = Api.text(body, "name");
        String role = Api.text(body, "role");
        if (role != null) {
            if (permissions.role(role).isEmpty()) return Api.error(HttpStatus.BAD_REQUEST, "Unknown role " + role);
            if (found.get().admin() && !WebUser.ADMIN_ROLE.equals(role) && admins() <= 1) {
                return Api.error(HttpStatus.CONFLICT, "The last admin cannot lose the admin role");
            }
        }
        WebUser updated = users.update(id, n -> {
            if (name != null && !name.isBlank()) n.put("name", name.strip());
            if (role != null) n.put("role", role);
        });
        return ResponseEntity.ok(row(updated));
    }

    /** Sets a new password, or resets to the default one when none is given; signs the user out everywhere. */
    @PostMapping("/users/{id}/password")
    public ResponseEntity<Object> setPassword(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        if (!users.exists(id)) return Api.error(HttpStatus.NOT_FOUND, "Unknown user " + id);
        String password = Api.text(body, "password");
        String hash = null;
        if (password != null && !password.isEmpty()) {
            String invalid = MeController.invalidPassword(id, password);
            if (invalid != null) return Api.error(HttpStatus.BAD_REQUEST, invalid);
            hash = PasswordHasher.hash(password);
        }
        users.setPassword(id, hash);
        sessions.revokeUser(id, null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Object> deleteUser(@PathVariable String id,
                                             @RequestAttribute(value = WebPrincipal.ATTR, required = false) WebPrincipal me) {
        Optional<WebUser> found = users.find(id);
        if (found.isEmpty()) return Api.error(HttpStatus.NOT_FOUND, "Unknown user " + id);
        if (me != null && me.id().equals(id)) return Api.error(HttpStatus.CONFLICT, "You cannot delete your own account");
        if (found.get().admin() && admins() <= 1) return Api.error(HttpStatus.CONFLICT, "The last admin cannot be deleted");
        users.delete(id);
        skills.deleteAllOwn(id);
        sessions.revokeUser(id, null);
        return ResponseEntity.noContent().build();
    }

    // ---- roles

    private Map<String, Object> row(Permissions.Role r, List<WebUser> all) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name());
        m.put("tools", r.tools());
        m.put("readOnly", r.readOnly());
        m.put("approval", r.approval());
        m.put("fixed", r.fixed());
        m.put("users", all.stream().filter(u -> r.name().equals(u.role())).count());
        return m;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roleList() {
        List<WebUser> all = users.all();
        return permissions.roles().stream().map(r -> row(r, all)).toList();
    }

    @PostMapping("/roles")
    public ResponseEntity<Object> createRole(@RequestBody(required = false) Map<String, Object> body) {
        String name = Api.text(body, "name");
        if (WebUser.ADMIN_ROLE.equals(name)) return Api.error(HttpStatus.CONFLICT, "The admin role is fixed");
        if (!Permissions.validName(name)) return Api.error(HttpStatus.BAD_REQUEST, "Role: 1-64 of a-z, 0-9, '_' and '-'");
        if (permissions.role(name).isPresent()) return Api.error(HttpStatus.CONFLICT, "Role " + name + " exists");
        return saveRole(name, body, HttpStatus.CREATED);
    }

    @PutMapping("/roles/{name}")
    public ResponseEntity<Object> updateRole(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body) {
        if (WebUser.ADMIN_ROLE.equals(name)) return Api.error(HttpStatus.FORBIDDEN, "The admin role is fixed");
        if (permissions.role(name).isEmpty()) return Api.error(HttpStatus.NOT_FOUND, "Unknown role " + name);
        return saveRole(name, body, HttpStatus.OK);
    }

    private ResponseEntity<Object> saveRole(String name, Map<String, Object> body, HttpStatus status) {
        List<String> tools = strings(body, "tools");
        if (tools == null) return Api.error(HttpStatus.BAD_REQUEST, "'tools' must be an array of strings");
        if (tools.stream().anyMatch(t -> t.isBlank() || t.length() > 200)) {
            return Api.error(HttpStatus.BAD_REQUEST, "Invalid tool entry");
        }
        boolean readOnly = body != null && Boolean.TRUE.equals(body.get("readOnly"));
        String approval = Api.text(body, "approval");
        try {
            Permissions.Role saved = permissions.save(new Permissions.Role(name, tools.stream().distinct().toList(),
                    readOnly, approval));
            return ResponseEntity.status(status).body(row(saved, users.all()));
        } catch (IllegalArgumentException e) {
            return Api.error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/roles/{name}")
    public ResponseEntity<Object> deleteRole(@PathVariable String name) {
        if (WebUser.ADMIN_ROLE.equals(name)) return Api.error(HttpStatus.FORBIDDEN, "The admin role is fixed");
        if (permissions.role(name).isEmpty()) return Api.error(HttpStatus.NOT_FOUND, "Unknown role " + name);
        long holders = users.all().stream().filter(u -> name.equals(u.role())).count();
        if (holders > 0) return Api.error(HttpStatus.CONFLICT, "Role " + name + " is held by " + holders + " user(s)");
        permissions.delete(name);
        return ResponseEntity.noContent().build();
    }

    // ---- catalog

    @GetMapping("/catalog")
    public ToolCatalogView.Catalog catalog() {
        return catalog.all();
    }

    // ---- global skills

    @GetMapping("/skills")
    public List<Map<String, Object>> skillList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillCatalog.Skill s : harness.skills().all()) {
            out.add(Map.of("name", s.name(), "description", s.description(), "source", "settings",
                    "shadows", List.of()));
        }
        for (SkillFiles.Skill s : skills.global()) {
            out.add(Map.of("name", s.name(), "description", s.description(), "source", "global",
                    "shadows", skills.ownersOf(s.name())));
        }
        return out;
    }

    @GetMapping("/skills/{name}")
    public ResponseEntity<Object> skill(@PathVariable String name) {
        Optional<SkillFiles.Skill> g = skills.findGlobal(name);
        if (g.isPresent()) {
            SkillFiles.Skill s = g.get();
            return ResponseEntity.ok(Map.of("name", s.name(), "description", s.description(), "source", "global",
                    "content", s.content()));
        }
        for (SkillCatalog.Skill s : harness.skills().all()) {
            if (!s.name().equals(name)) continue;
            try {
                return ResponseEntity.ok(Map.of("name", s.name(), "description", s.description(),
                        "source", "settings", "content", java.nio.file.Files.readString(s.path())));
            } catch (java.io.IOException e) {
                return Api.error(HttpStatus.NOT_FOUND, "Skill file unreadable: " + name);
            }
        }
        return Api.error(HttpStatus.NOT_FOUND, "Unknown skill " + name);
    }

    private boolean settingsSkill(String name) {
        return harness.skills().all().stream().anyMatch(s -> s.name().equals(name));
    }

    @PostMapping("/skills")
    public ResponseEntity<Object> createSkill(@RequestBody(required = false) Map<String, Object> body) {
        String name = Api.text(body, "name");
        if (!SkillFiles.validName(name)) return Api.error(HttpStatus.BAD_REQUEST, "Skill name: 1-64 of a-z, 0-9, '_' and '-'");
        if (settingsSkill(name) || skills.findGlobal(name).isPresent()) {
            return Api.error(HttpStatus.CONFLICT, "A skill named " + name + " exists");
        }
        return writeSkill(name, body, HttpStatus.CREATED);
    }

    @PutMapping("/skills/{name}")
    public ResponseEntity<Object> updateSkill(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body) {
        if (skills.findGlobal(name).isEmpty()) {
            return settingsSkill(name) ? Api.error(HttpStatus.FORBIDDEN, "Skills of the settings file are read-only")
                    : Api.error(HttpStatus.NOT_FOUND, "Unknown skill " + name);
        }
        return writeSkill(name, body, HttpStatus.OK);
    }

    private ResponseEntity<Object> writeSkill(String name, Map<String, Object> body, HttpStatus status) {
        try {
            SkillFiles.Skill s = skills.writeGlobal(name, Api.text(body, "description"), Api.text(body, "content"));
            return ResponseEntity.status(status).body(Map.of("name", s.name(), "description", s.description(),
                    "source", "global", "content", s.content(), "shadows", skills.ownersOf(name)));
        } catch (SkillFiles.SkillTooLargeException e) {
            return Api.error(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }
    }

    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Object> deleteSkill(@PathVariable String name) {
        if (!skills.deleteGlobal(name)) {
            return settingsSkill(name) ? Api.error(HttpStatus.FORBIDDEN, "Skills of the settings file are read-only")
                    : Api.error(HttpStatus.NOT_FOUND, "Unknown skill " + name);
        }
        return ResponseEntity.noContent().build();
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

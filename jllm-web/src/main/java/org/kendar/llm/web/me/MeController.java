package org.kendar.llm.web.me;

import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.web.Api;
import org.kendar.llm.web.admin.ToolCatalogView;
import org.kendar.llm.web.auth.CookieAuthentication;
import org.kendar.llm.web.auth.LoginThrottle;
import org.kendar.llm.web.auth.PasswordHasher;
import org.kendar.llm.web.auth.SessionStore;
import org.kendar.llm.web.commons.WebPrincipal;
import org.kendar.llm.web.users.Permissions;
import org.kendar.llm.web.commons.users.Role;
import org.kendar.llm.web.commons.users.SkillRepository;
import org.kendar.llm.web.commons.users.UserRepository;
import org.kendar.llm.web.commons.users.WebUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The signed-in user's own settings: password, tools switched off, skills (own ones, and which of all skills are
 * on). Every change is for the whole user, all chats, from their next turn. No id in the paths: a user can only
 * reach their own account.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository users;
    private final Permissions permissions;
    private final SkillRepository skills;
    private final SessionStore sessions;
    private final LoginThrottle throttle;
    private final AgentHarness harness;
    private final ToolCatalogView catalog;

    public MeController(UserRepository users, Permissions permissions, SkillRepository skills, SessionStore sessions,
                        LoginThrottle throttle, AgentHarness harness, ToolCatalogView catalog) {
        this.users = users;
        this.permissions = permissions;
        this.skills = skills;
        this.sessions = sessions;
        this.throttle = throttle;
        this.harness = harness;
        this.catalog = catalog;
    }

    private Optional<WebUser> user(WebPrincipal principal) {
        return principal == null ? Optional.empty() : users.find(principal.id());
    }

    // ---- password

    @PostMapping("/password")
    public ResponseEntity<Object> password(@RequestBody(required = false) Map<String, Object> body,
                                           @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                           WebPrincipal principal, HttpServletRequest request) {
        Optional<WebUser> found = user(principal);
        if (found.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        WebUser u = found.get();
        String key = "id:" + u.id();
        long locked = throttle.lockedFor(key);
        if (locked > 0) return Api.error(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts; retry in " + locked + " s");
        String current = Api.text(body, "current");
        String next = Api.text(body, "next");
        if (!PasswordHasher.verify(u.id(), current, u.password())) {
            throttle.failure(key);
            return Api.error(HttpStatus.UNAUTHORIZED, "Current password is wrong");
        }
        String invalid = invalidPassword(u.id(), next);
        if (invalid != null) return Api.error(HttpStatus.BAD_REQUEST, invalid);
        users.setPassword(u.id(), PasswordHasher.hash(next));
        sessions.revokeUser(u.id(), CookieAuthentication.token(request).orElse(null));
        return ResponseEntity.noContent().build();
    }

    /** Why a new password is refused; null when it is acceptable. */
    public static String invalidPassword(String id, String next) {
        if (next == null || next.length() < PasswordHasher.MIN_LENGTH) {
            return "The password needs at least " + PasswordHasher.MIN_LENGTH + " characters";
        }
        if (next.length() > 256) return "The password is too long";
        if (next.equals(PasswordHasher.defaultPassword(id))) return "The password cannot be the default one";
        return null;
    }

    // ---- tools

    @GetMapping("/tools")
    public ResponseEntity<Object> tools(@RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                       WebPrincipal principal) {
        Optional<WebUser> u = user(principal);
        if (u.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        Optional<Role> role = permissions.role(u.get().role());
        ToolCatalogView.Catalog c = role.map(r -> catalog.forUser(r, u.get()))
                .orElse(new ToolCatalogView.Catalog(List.of(), List.of(), List.of()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", c.tools());
        out.put("mcp", c.mcp());
        out.put("api", c.api());
        out.put("disabled", u.get().disabledTools());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/tools")
    public ResponseEntity<Object> setTools(@RequestBody(required = false) Map<String, Object> body,
                                           @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                           WebPrincipal principal) {
        Optional<WebUser> u = user(principal);
        if (u.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        List<String> disabled = strings(body, "disabled");
        if (disabled == null) return Api.error(HttpStatus.BAD_REQUEST, "'disabled' must be an array of strings");
        List<String> kept = permissions.role(u.get().role())
                .map(r -> catalog.keepMeaningful(r, disabled)).orElse(List.of());
        users.setDisabledTools(u.get().id(), kept);
        return ResponseEntity.noContent().build();
    }

    // ---- skills

    /** Whether the user's role and settings let them use the {@code skill} tool at all. */
    private boolean canUseSkills(WebUser u) {
        HarnessTool skill = harness.tools().get("skill");
        if (skill == null) skill = new SimpleTool("skill", "", null, "Skills", ToolKind.READ_ONLY, (c, i) -> "");
        HarnessTool tool = skill;
        return permissions.role(u.role())
                .map(r -> Permissions.policyOf(r, u, null).allows(tool)).orElse(false);
    }

    private Set<String> sharedNames() {
        Set<String> out = new HashSet<>();
        harness.skills().all().forEach(s -> out.add(s.name()));
        skills.global().forEach(s -> out.add(s.name()));
        return out;
    }

    @GetMapping("/skills")
    public ResponseEntity<Object> skillList(@RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                            WebPrincipal principal) {
        Optional<WebUser> found = user(principal);
        if (found.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        WebUser u = found.get();
        Set<String> off = new HashSet<>(u.disabledSkills());
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> shared = new HashSet<>();
        for (SkillCatalog.Skill s : harness.skills().all()) {
            shared.add(s.name());
            list.add(entry(s.name(), s.description(), "settings", !off.contains(s.name()), false));
        }
        for (SkillRepository.Skill s : skills.global()) {
            boolean shadowed = shared.contains(s.name());
            shared.add(s.name());
            list.add(entry(s.name(), s.description(), "global", !off.contains(s.name()), shadowed));
        }
        for (SkillRepository.Skill s : skills.own(u.id())) {
            list.add(entry(s.name(), s.description(), "own", !off.contains(s.name()), shared.contains(s.name())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("usable", canUseSkills(u));
        out.put("skills", list);
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> entry(String name, String description, String source, boolean enabled,
                                             boolean shadowed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description == null ? "" : description);
        m.put("source", source);
        m.put("enabled", enabled);
        m.put("shadowed", shadowed);
        return m;
    }

    @GetMapping("/skills/{name}")
    public ResponseEntity<Object> skill(@PathVariable String name,
                                        @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                        WebPrincipal principal) {
        Optional<WebUser> u = user(principal);
        if (u.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        Optional<SkillRepository.Skill> own = skills.findOwn(u.get().id(), name);
        if (own.isPresent()) return ResponseEntity.ok(body(own.get(), "own"));
        Optional<SkillRepository.Skill> global = skills.findGlobal(name);
        if (global.isPresent()) return ResponseEntity.ok(body(global.get(), "global"));
        for (SkillCatalog.Skill s : harness.skills().all()) {
            if (s.name().equals(name)) {
                try {
                    String text = s.text().read();
                    return ResponseEntity.ok(Map.of("name", s.name(), "description", s.description(),
                            "source", "settings", "content", text));
                } catch (java.io.IOException e) {
                    return Api.error(HttpStatus.NOT_FOUND, "Skill file unreadable: " + name);
                }
            }
        }
        return Api.error(HttpStatus.NOT_FOUND, "Unknown skill " + name);
    }

    static Map<String, Object> body(SkillRepository.Skill s, String source) {
        return Map.of("name", s.name(), "description", s.description(), "source", source, "content", s.content());
    }

    @PutMapping("/skills/enabled")
    public ResponseEntity<Object> setEnabledSkills(@RequestBody(required = false) Map<String, Object> body,
                                                   @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                                   WebPrincipal principal) {
        Optional<WebUser> u = user(principal);
        if (u.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        List<String> disabled = strings(body, "disabled");
        if (disabled == null) return Api.error(HttpStatus.BAD_REQUEST, "'disabled' must be an array of strings");
        Set<String> known = sharedNames();
        skills.own(u.get().id()).forEach(s -> known.add(s.name()));
        users.setDisabledSkills(u.get().id(), disabled.stream().filter(known::contains).distinct().toList());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/skills")
    public ResponseEntity<Object> createSkill(@RequestBody(required = false) Map<String, Object> body,
                                              @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                              WebPrincipal principal) {
        Optional<WebUser> found = user(principal);
        if (found.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        WebUser u = found.get();
        if (!canUseSkills(u)) return Api.error(HttpStatus.FORBIDDEN, "Skills are not available to your role");
        String name = Api.text(body, "name");
        if (!SkillRepository.validName(name)) {
            return Api.error(HttpStatus.BAD_REQUEST, "Skill name: 1-64 of a-z, 0-9, '_' and '-'");
        }
        if (sharedNames().contains(name) || skills.findOwn(u.id(), name).isPresent()) {
            return Api.error(HttpStatus.CONFLICT, "A skill named " + name + " exists");
        }
        if (skills.own(u.id()).size() >= SkillRepository.MAX_OWN) {
            return Api.error(HttpStatus.CONFLICT, "At most " + SkillRepository.MAX_OWN + " skills per user");
        }
        return write(u.id(), name, body, HttpStatus.CREATED);
    }

    @PutMapping("/skills/{name}")
    public ResponseEntity<Object> updateSkill(@PathVariable String name,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                              WebPrincipal principal) {
        Optional<WebUser> found = user(principal);
        if (found.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        WebUser u = found.get();
        if (skills.findOwn(u.id(), name).isEmpty()) {
            return sharedNames().contains(name) ? Api.error(HttpStatus.FORBIDDEN, "Only your own skills can be edited")
                    : Api.error(HttpStatus.NOT_FOUND, "Unknown skill " + name);
        }
        if (!canUseSkills(u)) return Api.error(HttpStatus.FORBIDDEN, "Skills are not available to your role");
        return write(u.id(), name, body, HttpStatus.OK);
    }

    private ResponseEntity<Object> write(String userId, String name, Map<String, Object> body, HttpStatus status) {
        try {
            SkillRepository.Skill s = skills.writeOwn(userId, name, Api.text(body, "description"),
                    Api.text(body, "content"));
            return ResponseEntity.status(status).body(body(s, "own"));
        } catch (SkillRepository.SkillTooLargeException e) {
            return Api.error(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }
    }

    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Object> deleteSkill(@PathVariable String name,
                                              @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                              WebPrincipal principal) {
        Optional<WebUser> found = user(principal);
        if (found.isEmpty()) return Api.error(HttpStatus.UNAUTHORIZED, "Sign in first");
        WebUser u = found.get();
        if (!skills.deleteOwn(u.id(), name)) {
            return sharedNames().contains(name) ? Api.error(HttpStatus.FORBIDDEN, "Only your own skills can be deleted")
                    : Api.error(HttpStatus.NOT_FOUND, "Unknown skill " + name);
        }
        if (u.disabledSkills().contains(name) && !sharedNames().contains(name)) {
            users.setDisabledSkills(u.id(), u.disabledSkills().stream().filter(n -> !n.equals(name)).toList());
        }
        return ResponseEntity.noContent().build();
    }

    /** A list of strings from the body; null when absent or not such a list. */
    static List<String> strings(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s)) return null;
            out.add(s);
        }
        return out.size() > 1000 ? null : out;
    }
}

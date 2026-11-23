package org.kendar.llm.web.commons.me;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.web.commons.Api;
import org.kendar.llm.web.commons.ApiException;
import org.kendar.llm.web.commons.Reply;
import org.kendar.llm.web.commons.admin.ToolCatalogView;
import org.kendar.llm.web.commons.auth.LoginThrottle;
import org.kendar.llm.web.commons.auth.PasswordHasher;
import org.kendar.llm.web.commons.users.Permissions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The signed-in user's own settings: password, tools switched off, skills (own ones, and which of all skills are
 * on). Every change is for the whole user, all chats, from their next turn. No id in the paths: a user can only
 * reach their own account.
 */
public class MeApi {

    private final UserRepository users;
    private final Permissions permissions;
    private final SkillRepository skills;
    private final LoginSessionRepository sessions;
    private final LoginThrottle throttle;
    private final AgentHarness harness;
    private final ToolCatalogView catalog;

    public MeApi(UserRepository users, Permissions permissions, SkillRepository skills, LoginSessionRepository sessions,
                 LoginThrottle throttle, AgentHarness harness, ToolCatalogView catalog) {
        this.users = users;
        this.permissions = permissions;
        this.skills = skills;
        this.sessions = sessions;
        this.throttle = throttle;
        this.harness = harness;
        this.catalog = catalog;
    }

    /** The stored user behind the caller; 401 when there is none. */
    private User user(Principal principal) {
        return (principal == null ? Optional.<User>empty() : users.find(principal.id()))
                .orElseThrow(() -> new ApiException(401, "Sign in first"));
    }

    // ---- password

    /**
     * Changes the password and ends the user's other sessions.
     *
     * @param sessionToken the session of this request, which stays signed in; null for none
     */
    public void password(Map<String, Object> body, Principal principal, String sessionToken) {
        User u = user(principal);
        if (u.passwordless()) throw new ApiException(409, "This account signs in through the identity provider");
        String key = "id:" + u.id();
        long locked = throttle.lockedFor(key);
        if (locked > 0) throw new ApiException(429, "Too many failed attempts; retry in " + locked + " s");
        String current = Api.text(body, "current");
        String next = Api.text(body, "next");
        if (!PasswordHasher.verify(u.id(), current, u.password())) {
            throttle.failure(key);
            throw new ApiException(401, "Current password is wrong");
        }
        String invalid = invalidPassword(u.id(), next);
        if (invalid != null) throw new ApiException(400, invalid);
        users.setPassword(u.id(), PasswordHasher.hash(next));
        sessions.revokeUser(u.id(), sessionToken);
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

    public Map<String, Object> tools(Principal principal) {
        User u = user(principal);
        Optional<Role> role = permissions.role(u.role());
        ToolCatalogView.Catalog c = role.map(r -> catalog.forUser(r, u))
                .orElse(new ToolCatalogView.Catalog(List.of(), List.of(), List.of()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", c.tools());
        out.put("mcp", c.mcp());
        out.put("api", c.api());
        out.put("disabled", u.disabledTools());
        return out;
    }

    public void setTools(Map<String, Object> body, Principal principal) {
        User u = user(principal);
        List<String> disabled = strings(body, "disabled");
        if (disabled == null) throw new ApiException(400, "'disabled' must be an array of strings");
        List<String> kept = permissions.role(u.role())
                .map(r -> catalog.keepMeaningful(r, disabled)).orElse(List.of());
        users.setDisabledTools(u.id(), kept);
    }

    // ---- skills

    /** Whether the user's role and settings let them use the {@code skill} tool at all. */
    private boolean canUseSkills(User u) {
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

    public Map<String, Object> skillList(Principal principal) {
        User u = user(principal);
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
        return out;
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

    public Map<String, Object> skill(String name, Principal principal) {
        User u = user(principal);
        Optional<SkillRepository.Skill> own = skills.findOwn(u.id(), name);
        if (own.isPresent()) return body(own.get(), "own");
        Optional<SkillRepository.Skill> global = skills.findGlobal(name);
        if (global.isPresent()) return body(global.get(), "global");
        for (SkillCatalog.Skill s : harness.skills().all()) {
            if (s.name().equals(name)) {
                try {
                    String text = s.text().read();
                    return Map.of("name", s.name(), "description", s.description(),
                            "source", "settings", "content", text);
                } catch (java.io.IOException e) {
                    throw new ApiException(404, "Skill file unreadable: " + name);
                }
            }
        }
        throw new ApiException(404, "Unknown skill " + name);
    }

    static Map<String, Object> body(SkillRepository.Skill s, String source) {
        return Map.of("name", s.name(), "description", s.description(), "source", source, "content", s.content());
    }

    public void setEnabledSkills(Map<String, Object> body, Principal principal) {
        User u = user(principal);
        List<String> disabled = strings(body, "disabled");
        if (disabled == null) throw new ApiException(400, "'disabled' must be an array of strings");
        Set<String> known = sharedNames();
        skills.own(u.id()).forEach(s -> known.add(s.name()));
        users.setDisabledSkills(u.id(), disabled.stream().filter(known::contains).distinct().toList());
    }

    /** Creates an own skill: answers 201 with it. */
    public Reply<Map<String, Object>> createSkill(Map<String, Object> body, Principal principal) {
        User u = user(principal);
        if (!canUseSkills(u)) throw new ApiException(403, "Skills are not available to your role");
        String name = Api.text(body, "name");
        if (!SkillRepository.validName(name)) {
            throw new ApiException(400, "Skill name: 1-64 of a-z, 0-9, '_' and '-'");
        }
        if (sharedNames().contains(name) || skills.findOwn(u.id(), name).isPresent()) {
            throw new ApiException(409, "A skill named " + name + " exists");
        }
        if (skills.own(u.id()).size() >= SkillRepository.MAX_OWN) {
            throw new ApiException(409, "At most " + SkillRepository.MAX_OWN + " skills per user");
        }
        return Reply.status(201, write(u.id(), name, body));
    }

    public Map<String, Object> updateSkill(String name, Map<String, Object> body, Principal principal) {
        User u = user(principal);
        if (skills.findOwn(u.id(), name).isEmpty()) {
            throw sharedNames().contains(name) ? new ApiException(403, "Only your own skills can be edited")
                    : new ApiException(404, "Unknown skill " + name);
        }
        if (!canUseSkills(u)) throw new ApiException(403, "Skills are not available to your role");
        return write(u.id(), name, body);
    }

    private Map<String, Object> write(String userId, String name, Map<String, Object> body) {
        try {
            SkillRepository.Skill s = skills.writeOwn(userId, name, Api.text(body, "description"),
                    Api.text(body, "content"));
            return body(s, "own");
        } catch (SkillRepository.SkillTooLargeException e) {
            throw new ApiException(413, e.getMessage());
        }
    }

    public void deleteSkill(String name, Principal principal) {
        User u = user(principal);
        if (!skills.deleteOwn(u.id(), name)) {
            throw sharedNames().contains(name) ? new ApiException(403, "Only your own skills can be deleted")
                    : new ApiException(404, "Unknown skill " + name);
        }
        if (u.disabledSkills().contains(name) && !sharedNames().contains(name)) {
            users.setDisabledSkills(u.id(), u.disabledSkills().stream().filter(n -> !n.equals(name)).toList());
        }
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

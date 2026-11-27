package org.kendar.llm.web.commons.auth;

import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.User;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the one role the identity provider groups give: every role whose {@code idp} setting has a pattern matching
 * one of the groups is a candidate; the lowest priority wins, ties go to the role name. A user has exactly one role, so
 * the other candidates are ignored. The {@code admin} role is never picked unless {@code allowAdmin}.
 */
public final class RoleMapper {

    /** A role whose {@code idp} setting matches a group. */
    public record Match(String role, int priority, String group, String pattern, String skipped) {
    }

    /**
     * The result.
     *
     * @param role    the role picked; null when none matches
     * @param matches every matching role, best first, the picked one included
     * @param notes   roles whose {@code idp} setting is unreadable, and similar
     */
    public record Decision(String role, String group, String pattern, List<Match> matches, List<String> notes) {
        public boolean matched() {
            return role != null;
        }
    }

    private RoleMapper() {
    }

    /** The role for {@code groups} among {@code roles}. */
    public static Decision decide(RoleMappingOptions options, List<Role> roles, List<String> groups) {
        List<Match> matches = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Role r : roles) {
            var raw = r.setting(IdpSetting.KEY);
            if (raw == null) continue;
            IdpSetting idp;
            try {
                idp = IdpSetting.parse(raw);
            } catch (IllegalArgumentException e) {
                notes.add("Role " + r.name() + ": settings 'idp' ignored, " + e.getMessage());
                continue;
            }
            for (String g : groups) {
                String p = idp.patternFor(g);
                if (p == null) continue;
                String skipped = User.ADMIN_ROLE.equals(r.name()) && !options.allowAdmin()
                        ? "the admin role needs allowAdmin" : null;
                matches.add(new Match(r.name(), idp.priority(), g, p, skipped));
                break;
            }
        }
        matches.sort(Comparator.comparingInt(Match::priority).thenComparing(Match::role));
        Match best = matches.stream().filter(m -> m.skipped() == null).findFirst().orElse(null);
        return best == null ? new Decision(null, null, null, matches, notes)
                : new Decision(best.role(), best.group(), best.pattern(), matches, notes);
    }

    /** The decision as the preview endpoint shows it, with the outcome of a sign-in under {@code options}. */
    public static Map<String, Object> view(RoleMappingOptions options, String defaultRole, List<String> groups,
                                           Decision d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", options.enabled());
        out.put("groups", groups);
        out.put("role", d.role());
        out.put("group", d.group());
        out.put("pattern", d.pattern());
        List<Map<String, Object>> ms = new ArrayList<>();
        for (Match m : d.matches()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.role());
            mm.put("priority", m.priority());
            mm.put("group", m.group());
            mm.put("pattern", m.pattern());
            mm.put("chosen", m.role().equals(d.role()));
            mm.put("skipped", m.skipped());
            ms.add(mm);
        }
        out.put("matches", ms);
        out.put("notes", d.notes());
        String outcome;
        if (d.matched()) {
            outcome = "role " + d.role() + " (group " + d.group() + ")";
        } else {
            outcome = switch (options.onNoMatch()) {
                case DEFAULT -> defaultRole == null ? "no match, and no default role: the role is kept"
                        : "no match: the default role " + defaultRole;
                case KEEP -> "no match: the current role is kept (a new user gets the default role)";
                case REFUSE -> "no match: the sign-in is refused (no_role)";
            };
        }
        out.put("outcome", outcome);
        out.put("rule", "One role per user: among the matching roles the lowest priority wins, ties by role name");
        if (!options.enabled()) out.put("note", "Role mapping is disabled: this is what a sign-in would do once enabled");
        return out;
    }
}

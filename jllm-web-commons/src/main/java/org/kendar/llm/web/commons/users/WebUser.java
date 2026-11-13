package org.kendar.llm.web.commons.users;

import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.web.commons.WebPrincipal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A user, as kept by a {@link UserRepository}.
 *
 * @param role           name of a role ({@link RoleRepository}); {@code admin} is the fixed administrator role
 * @param credentials    per call source ({@code mcp:<server>}, {@code api:<name>}) the headers to add; a value of the
 *                       form {@code env:NAME} is read from the environment at call time, anything else is ignored
 * @param password       stored password hash ({@code pbkdf2$...}); null = the default password (see
 *                       {@code PasswordHasher#defaultPassword})
 * @param disabledTools  tool name patterns the user switched off for themselves (only narrows the role)
 * @param disabledSkills skill names the user switched off for themselves
 */
public record WebUser(String id, String name, String role, Map<String, Map<String, String>> credentials,
                      String password, List<String> disabledTools, List<String> disabledSkills)
        implements WebPrincipal {

    public static final String ADMIN_ROLE = "admin";

    public WebUser {
        credentials = credentials == null ? Map.of() : credentials;
        disabledTools = disabledTools == null ? List.of() : List.copyOf(disabledTools);
        disabledSkills = disabledSkills == null ? List.of() : List.copyOf(disabledSkills);
    }

    public WebUser(String id, String name, String role, Map<String, Map<String, String>> credentials) {
        this(id, name, role, credentials, null, null, null);
    }

    @Override
    public boolean admin() {
        return ADMIN_ROLE.equals(role);
    }

    public boolean usesDefaultPassword() {
        return password == null || password.isBlank();
    }

    public HarnessPrincipal principal() {
        return new HarnessPrincipal(id, Set.of(role == null ? "none" : role), Map.of("name", name == null ? id : name));
    }
}

package org.kendar.llm.harness.identity;

import org.kendar.llm.harness.auth.HarnessPrincipal;

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
 * @param externalId     {@code <issuer>#<sub>} of the identity provider account linked to the user; null = none
 * @param email          optional address, used to link a pre-created user to an identity provider account
 * @param settings       free-text JSON object of the user's own settings; {@code {}} when none. Validated when stored
 *                       ({@link UserRepository#setSettings}), so what is read back is always a JSON object
 */
public record User(String id, String name, String role, Map<String, Map<String, String>> credentials,
                      String password, List<String> disabledTools, List<String> disabledSkills, String externalId, String email,
                   String settings)
        implements Principal {

    public static final String ADMIN_ROLE = "admin";

    public User {
        credentials = credentials == null ? Map.of() : credentials;
        disabledTools = disabledTools == null ? List.of() : List.copyOf(disabledTools);
        disabledSkills = disabledSkills == null ? List.of() : List.copyOf(disabledSkills);
        externalId = externalId == null || externalId.isBlank() ? null : externalId;
        email = email == null || email.isBlank() ? null : email.strip();
        settings = settings == null || settings.isBlank() ? "{}" : settings;
    }

    public User(String id, String name, String role, Map<String, Map<String, String>> credentials) {
        this(id, name, role, credentials, null, null, null, null, null, null);
    }

    @Override
    public boolean admin() {
        return ADMIN_ROLE.equals(role);
    }

    /** Whether the user is linked to an identity provider account. */
    public boolean external() {
        return externalId != null;
    }

    /** A linked user without a password hash signs in through the identity provider only. */
    public boolean passwordless() {
        return external() && (password == null || password.isBlank());
    }

    /** The default password applies only to users without an identity provider account. */
    public boolean usesDefaultPassword() {
        return !external() && (password == null || password.isBlank());
    }

    public HarnessPrincipal principal() {
        return new HarnessPrincipal(id, Set.of(role == null ? "none" : role), Map.of("name", name == null ? id : name));
    }
}

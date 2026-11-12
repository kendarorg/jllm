package org.kendar.llm.web;

import org.kendar.llm.harness.auth.HarnessPrincipal;

import java.util.Map;
import java.util.Set;

/**
 * A sample user, read from {@code webauth/users/<id>.json}. The sign-in is a fake picker: no password, no check.
 *
 * @param role        one of {@code guest}, {@code reader}, {@code admin}; any other value gets no tools
 * @param credentials per call source ({@code mcp:<server>}, {@code api:<name>}) the headers to add; a value of the form
 *                    {@code env:NAME} is read from the environment at call time, anything else is ignored
 */
public record WebUser(String id, String name, String role, Map<String, Map<String, String>> credentials) {

    public WebUser {
        credentials = credentials == null ? Map.of() : credentials;
    }

    public boolean admin() {
        return "admin".equals(role);
    }

    public HarnessPrincipal principal() {
        return new HarnessPrincipal(id, Set.of(role == null ? "none" : role), Map.of("name", name == null ? id : name));
    }
}

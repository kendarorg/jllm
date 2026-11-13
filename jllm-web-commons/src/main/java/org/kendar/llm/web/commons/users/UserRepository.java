package org.kendar.llm.web.commons.users;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where the web users are kept. Read on each use, so a change applies at once. The reference application keeps one
 * file per user.
 */
public interface UserRepository {

    Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * Credential values must be {@code env:NAME} references: secrets stay out of the store.
     *
     * @throws IllegalArgumentException on any other value
     */
    static void checkCredentials(Map<String, Map<String, String>> credentials) {
        if (credentials == null) return;
        credentials.forEach((source, headers) -> {
            if (headers == null) return;
            headers.forEach((header, value) -> {
                if (value == null || !value.startsWith("env:") || value.length() == 4) {
                    throw new IllegalArgumentException("Header " + header + " for " + source
                            + " must be an env:NAME reference");
                }
            });
        });
    }

    /** The user; empty for an invalid or unknown id, or an unreadable record (logged). */
    Optional<WebUser> find(String id);

    /** Every readable user, by id. */
    List<WebUser> all();

    boolean exists(String id);

    /**
     * @param name         display name; blank = the id
     * @param passwordHash null = the default password
     * @throws IllegalArgumentException on an invalid id
     * @throws IllegalStateException    when the user exists
     */
    WebUser create(String id, String name, String role, String passwordHash);

    /** @throws NoSuchElementException when the user does not exist */
    WebUser setName(String id, String name);

    /** @throws NoSuchElementException when the user does not exist */
    WebUser setRole(String id, String role);

    /**
     * @param passwordHash null = back to the default password
     * @throws NoSuchElementException when the user does not exist
     */
    WebUser setPassword(String id, String passwordHash);

    /**
     * Replaces the headers per call source ({@code mcp:<server>}, {@code api:<name>}).
     *
     * @throws IllegalArgumentException on a value that is not an {@code env:NAME} reference
     * @throws NoSuchElementException   when the user does not exist
     */
    WebUser setCredentials(String id, Map<String, Map<String, String>> credentials);

    /** @throws NoSuchElementException when the user does not exist */
    WebUser setDisabledTools(String id, List<String> patterns);

    /** @throws NoSuchElementException when the user does not exist */
    WebUser setDisabledSkills(String id, List<String> names);

    /** @return whether the user existed */
    boolean delete(String id);
}

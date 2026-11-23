package org.kendar.llm.harness.identity;

import java.util.List;
import java.util.Optional;

/**
 * Where the roles an admin defines are kept; the fixed {@code admin} role is not stored (the application enforces it).
 * Read on each use. The reference application keeps one file per role.
 */
public interface RoleRepository {

    /** The role; empty when unknown or unreadable (logged). */
    Optional<Role> find(String name);

    /** Every readable role, by name. */
    List<Role> all();

    /** Inserts or replaces the role. */
    void save(Role role);

    /** @return whether the role existed */
    boolean delete(String name);
}

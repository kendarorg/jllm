package org.kendar.llm.harness.identity;

import java.util.List;
import java.util.Optional;

/**
 * Where the roles an admin defines are kept. The fixed {@code admin} role may be stored (its approval; the SQL store
 * always has its row, so users can reference it); the application enforces that it is never deleted. Read on each use. The reference application keeps one file per role.
 */
public interface RoleRepository {

    /** The role; empty when unknown or unreadable (logged). */
    Optional<Role> find(String name);

    /** Every readable role, by name. */
    List<Role> all();

    /** Inserts or replaces the role. */
    void save(Role role);

    /**
     * @return whether the role existed
     * @throws RoleInUseException when a store that knows its users (SQL) still has users holding the role; callers
     *                            that delete through the file store check the users themselves
     * @throws IllegalArgumentException for the {@code admin} role (SQL)
     */
    boolean delete(String name);
}

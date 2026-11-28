package org.kendar.llm.harness.workflows;

import java.util.Set;

/**
 * Published by a multi-user host: which roles may mark a flow as unattended (nobody can answer its permission
 * questions). Without this service (single user, the command line) every user may.
 */
@FunctionalInterface
public interface UnattendedRoles {

    /** Whether a user holding these roles may own an unattended flow. Administrators always may, whatever this says. */
    boolean allowed(Set<String> roles);
}

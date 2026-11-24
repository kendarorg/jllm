package org.kendar.llm.harness.identity;

/** A user was given a role that does not exist. */
public final class UnknownRoleException extends IllegalArgumentException {

    public UnknownRoleException(String role) {
        super("Unknown role " + role);
    }
}

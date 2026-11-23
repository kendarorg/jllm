package org.kendar.llm.harness.identity;

import java.util.List;

/** A role cannot be deleted while users still hold it. */
public final class RoleInUseException extends IllegalStateException {

    private static final int LISTED = 10;

    private final String role;
    private final List<String> userIds;

    public RoleInUseException(String role, List<String> userIds) {
        super("Role " + role + " is held by " + userIds.size() + " user(s): "
                + String.join(", ", userIds.stream().limit(LISTED).toList())
                + (userIds.size() > LISTED ? ", ..." : ""));
        this.role = role;
        this.userIds = List.copyOf(userIds);
    }

    public String role() {
        return role;
    }

    public List<String> userIds() {
        return userIds;
    }
}

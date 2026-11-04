package org.kendar.llm.harness.permission;

public enum PermissionMode {
    /** Read-only tools run freely; everything flagged requiresApproval asks. Not offered in the /mode menu. */
    DEFAULT,
    /** Nothing asks (deny rules still apply). */
    AUTO,
    /** The working directory is read-only; the scratch directory stays writable; shell asks as in default. */
    PLAN,
    /** File edits run without asking; other flagged tools still ask. */
    EDIT;

    /** Unknown or null → {@link #DEFAULT}; the former name {@code bypass} is {@link #AUTO}. */
    public static PermissionMode parse(String s) {
        if (s == null) return DEFAULT;
        String name = s.trim().toUpperCase();
        if (name.equals("BYPASS")) return AUTO;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}

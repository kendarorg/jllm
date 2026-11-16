package org.kendar.llm.harness.permission;

/**
 * What an administrator decided for a tool of a role.
 * {@code ALLOW}: runs without any question, also in an unattended flow and with untrusted content in the chat;
 * {@code ASK}: the user is asked; {@code DENY}: never runs.
 */
public enum ToolPermission {
    ALLOW, ASK, DENY;

    public static ToolPermission parse(String text) {
        if (text != null) {
            for (ToolPermission p : values()) {
                if (p.name().equalsIgnoreCase(text.trim())) return p;
            }
        }
        throw new IllegalArgumentException("A tool permission must be \"allow\", \"ask\" or \"deny\": " + text);
    }
}

package org.kendar.llm.harness.agent;

/**
 * Process level switches of the host, fixed when the harness is built.
 *
 * @param unattended      nobody can answer a permission question of any chat (the command line with {@code --prompt},
 *                        piped input or a CI environment): every one is denied. Web chats are unattended per role
 *                        instead
 * @param skipPermissions command line {@code --dangerously-skip-permissions}: a call that would ask is allowed. Explicit
 *                        deny rules, hook denials and the administrator's deny still apply. Only the command line sets
 *                        it; the web host builds its harness with {@link #NORMAL}
 */
public record RunPolicy(boolean unattended, boolean skipPermissions) {

    /** Questions are asked, nothing is waived. */
    public static final RunPolicy NORMAL = new RunPolicy(false, false);
}

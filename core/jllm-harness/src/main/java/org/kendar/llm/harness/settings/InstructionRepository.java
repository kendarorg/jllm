package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.identity.UserRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The instructions of the system prompt, kept by a {@link ConfigProvider}: the replacement and the addition of the
 * system prompt ({@link Scope#SYSTEM}), the global instructions every chat gets ({@link Scope#GLOBAL}), those of a
 * role ({@link Scope#ROLE}) and those of a user ({@link Scope#USER}). Written by administrators only; no chat
 * directory is ever a source.
 */
public interface InstructionRepository {

    /** Who an instruction is for. */
    enum Scope {
        /** The system prompt itself: {@link #SYSTEM_REPLACE} replaces the built-in one, {@link #SYSTEM_APPEND} is added. */
        SYSTEM,
        /** Added to the system prompt of every chat. */
        GLOBAL,
        /** Added for the users of a role (owner = role name). */
        ROLE,
        /** Added for one user (owner = user id). */
        USER
    }

    Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    Pattern ROLE = Pattern.compile("[a-z0-9_-]{1,64}");
    String SYSTEM_REPLACE = "replace";
    String SYSTEM_APPEND = "append";
    int MAX_BYTES = 64 * 1024;

    /** One instruction text. {@code owner} is empty for {@link Scope#SYSTEM} and {@link Scope#GLOBAL}. */
    record Instruction(Scope scope, String owner, String name, String content) {
        public Instruction {
            owner = owner == null ? "" : owner;
            content = content == null ? "" : content;
        }
    }

    /** Nothing stored, nothing writable. */
    InstructionRepository NONE = new InstructionRepository() {
        @Override
        public List<Instruction> list(Scope scope, String owner) {
            return List.of();
        }

        @Override
        public Optional<Instruction> find(Scope scope, String owner, String name) {
            return Optional.empty();
        }

        @Override
        public Instruction write(Scope scope, String owner, String name, String content) {
            throw new UnsupportedOperationException("Read-only");
        }

        @Override
        public boolean delete(Scope scope, String owner, String name) {
            return false;
        }

        @Override
        public void deleteOwned(Scope scope, String owner) {
        }
    };

    /** The instructions of that scope and owner, by name. */
    List<Instruction> list(Scope scope, String owner);

    Optional<Instruction> find(Scope scope, String owner, String name);

    /**
     * Inserts or replaces.
     *
     * @throws IllegalArgumentException see {@link #check}
     */
    Instruction write(Scope scope, String owner, String name, String content);

    boolean delete(Scope scope, String owner, String name);

    /** Removes every instruction of an owner (the role or user is being deleted). */
    void deleteOwned(Scope scope, String owner);

    /** @throws IllegalArgumentException on an invalid owner or name, or content over {@link #MAX_BYTES} */
    static void check(Scope scope, String owner, String name, String content) {
        if (scope == null) throw new IllegalArgumentException("Missing scope");
        checkOwner(scope, owner);
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid instruction name: " + name);
        }
        if (scope == Scope.SYSTEM && !SYSTEM_REPLACE.equals(name) && !SYSTEM_APPEND.equals(name)) {
            throw new IllegalArgumentException("The system prompt instructions are '" + SYSTEM_REPLACE + "' and '"
                    + SYSTEM_APPEND + "'");
        }
        if (content != null && content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Instruction larger than " + (MAX_BYTES / 1024) + " KB");
        }
    }

    /** @throws IllegalArgumentException when the owner does not fit the scope */
    static void checkOwner(Scope scope, String owner) {
        String o = owner == null ? "" : owner;
        boolean ok = switch (scope) {
            case SYSTEM, GLOBAL -> o.isEmpty();
            case ROLE -> ROLE.matcher(o).matches();
            case USER -> UserRepository.validId(o);
        };
        if (!ok) throw new IllegalArgumentException("Invalid owner '" + o + "' for scope " + scope);
    }

    /** The text replacing the built-in system prompt; empty when there is none. */
    default Optional<String> systemPromptReplace() {
        return find(Scope.SYSTEM, "", SYSTEM_REPLACE).map(Instruction::content).filter(s -> !s.isBlank());
    }

    /** The text added to the system prompt; empty when there is none. */
    default String systemPromptAppend() {
        return find(Scope.SYSTEM, "", SYSTEM_APPEND).map(Instruction::content).orElse("");
    }

    /** The global instructions rendered for the system prompt, each under its name; empty when there are none. */
    default String renderGlobal() {
        StringBuilder sb = new StringBuilder();
        for (Instruction i : list(Scope.GLOBAL, "")) {
            if (i.content().isBlank()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("Instructions from ").append(i.name()).append(":\n").append(i.content().strip());
        }
        return sb.toString();
    }

    /** The instructions of a role and of a user, rendered for the system prompt of their chats; empty when none. */
    default String renderFor(String role, String userId) {
        StringBuilder sb = new StringBuilder();
        if (role != null && ROLE.matcher(role).matches()) render(sb, list(Scope.ROLE, role), "role " + role);
        if (userId != null && UserRepository.validId(userId)) render(sb, list(Scope.USER, userId), "this user");
        return sb.toString();
    }

    private static void render(StringBuilder sb, List<Instruction> list, String who) {
        for (Instruction i : list) {
            if (i.content().isBlank()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("Instructions for ").append(who).append(" (").append(i.name()).append("):\n")
                    .append(i.content().strip());
        }
    }
}

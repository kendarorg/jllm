package org.kendar.llm.web.commons.users;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Skills managed from the web: global ones (written by the admin, offered to every user) and each user's own. Read on
 * each use. The reference application keeps them as markdown files.
 */
public interface SkillRepository {

    Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");
    int MAX_BYTES = 64 * 1024;
    int MAX_OWN = 50;

    /** A skill: {@code content} is the instructions, {@code description} when to use them. */
    record Skill(String name, String description, String content) {
        public Skill {
            description = description == null ? "" : description;
            content = content == null ? "" : content;
        }
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** One line, stripped. */
    static String cleanDescription(String description) {
        return description == null ? "" : description.replaceAll("[\\r\\n]+", " ").strip();
    }

    /** @throws SkillTooLargeException when description and content together exceed {@link #MAX_BYTES} */
    static void checkSize(String description, String content) {
        long size = cleanDescription(description).getBytes(StandardCharsets.UTF_8).length
                + (content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
        if (size > MAX_BYTES) throw new SkillTooLargeException();
    }

    List<Skill> global();

    /** The user's own skills; empty for an invalid id. */
    List<Skill> own(String userId);

    Optional<Skill> findGlobal(String name);

    Optional<Skill> findOwn(String userId, String name);

    /**
     * Inserts or replaces.
     *
     * @throws IllegalArgumentException on an invalid name
     * @throws SkillTooLargeException   see {@link #checkSize}
     */
    Skill writeGlobal(String name, String description, String content);

    /**
     * Inserts or replaces.
     *
     * @throws IllegalArgumentException on an invalid name or user id
     * @throws SkillTooLargeException   see {@link #checkSize}
     */
    Skill writeOwn(String userId, String name, String description, String content);

    boolean deleteGlobal(String name);

    boolean deleteOwn(String userId, String name);

    /** Removes every own skill of a user (the user is being deleted). */
    void deleteAllOwn(String userId);

    /** Ids of the users having an own skill of that name, sorted. */
    List<String> ownersOf(String name);

    /** The skill would be larger than {@link #MAX_BYTES}. */
    final class SkillTooLargeException extends IllegalArgumentException {
        public SkillTooLargeException() {
            super("Skill larger than " + (MAX_BYTES / 1024) + " KB");
        }
    }
}

package org.kendar.llm.web.users;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill files managed from the web: global ones ({@code <webauth>/global-skills/<name>.md}, written by the admin,
 * offered to every user) and each user's own ({@code <webauth>/user-skills/<userId>/<name>.md}). A file is markdown
 * with a front matter holding {@code name} and {@code description}; it is read on each use.
 */
public final class SkillFiles {

    public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_OWN = 50;

    /** A skill file: {@code content} is the body, without the front matter. */
    public record Skill(String name, String description, String content, Path path) {
    }

    private final Path global;
    private final Path users;

    public SkillFiles(Path webauth) {
        this.global = webauth.resolve("global-skills");
        this.users = webauth.resolve("user-skills");
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public List<Skill> global() {
        return list(global);
    }

    public List<Skill> own(String userId) {
        return UserDirectory.validId(userId) ? list(users.resolve(userId)) : List.of();
    }

    public Optional<Skill> findGlobal(String name) {
        return read(global, name);
    }

    public Optional<Skill> findOwn(String userId, String name) {
        return UserDirectory.validId(userId) ? read(users.resolve(userId), name) : Optional.empty();
    }

    public Skill writeGlobal(String name, String description, String content) {
        return write(global, name, description, content);
    }

    public Skill writeOwn(String userId, String name, String description, String content) {
        if (!UserDirectory.validId(userId)) throw new IllegalArgumentException("Invalid user id");
        return write(users.resolve(userId), name, description, content);
    }

    public boolean deleteGlobal(String name) {
        return validName(name) && JsonFiles.delete(global.resolve(name + ".md"));
    }

    public boolean deleteOwn(String userId, String name) {
        return UserDirectory.validId(userId) && validName(name)
                && JsonFiles.delete(users.resolve(userId).resolve(name + ".md"));
    }

    /** Removes every own skill of a user (the user is being deleted). */
    public void deleteAllOwn(String userId) {
        if (!UserDirectory.validId(userId)) return;
        Path dir = users.resolve(userId);
        if (!Files.isDirectory(dir)) return;
        synchronized (JsonFiles.LOCK) {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Ids of the users having an own skill of that name. */
    public List<String> ownersOf(String name) {
        if (!validName(name) || !Files.isDirectory(users)) return List.of();
        try (Stream<Path> s = Files.list(users)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(UserDirectory::validId)
                    .filter(id -> Files.isRegularFile(users.resolve(id).resolve(name + ".md")))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Skill> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .map(n -> n.substring(0, n.length() - 3))
                    .filter(SkillFiles::validName)
                    .sorted()
                    .map(n -> read(dir, n))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Skill> read(Path dir, String name) {
        if (!validName(name)) return Optional.empty();
        Path file = dir.resolve(name + ".md");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String description = "";
            String body = text;
            if (text.startsWith("---\n")) {
                int end = text.indexOf("\n---", 4);
                if (end > 0) {
                    for (String line : text.substring(4, end).split("\n")) {
                        if (line.startsWith("description:")) description = line.substring(12).strip();
                    }
                    int after = text.indexOf('\n', end + 4);
                    body = after < 0 ? "" : text.substring(after + 1);
                }
            }
            return Optional.of(new Skill(name, description, body, file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Skill write(Path dir, String name, String description, String content) {
        if (!validName(name)) throw new IllegalArgumentException("Invalid skill name: " + name);
        String desc = description == null ? "" : description.replaceAll("[\\r\\n]+", " ").strip();
        String text = "---\nname: " + name + "\ndescription: " + desc + "\n---\n" + (content == null ? "" : content);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new SkillTooLargeException();
        }
        Path file = dir.resolve(name + ".md");
        JsonFiles.writeString(file, text);
        return read(dir, name).orElseThrow();
    }

    /** The skill file would be larger than {@link #MAX_BYTES}. */
    public static final class SkillTooLargeException extends IllegalArgumentException {
        public SkillTooLargeException() {
            super("Skill larger than " + (MAX_BYTES / 1024) + " KB");
        }
    }
}

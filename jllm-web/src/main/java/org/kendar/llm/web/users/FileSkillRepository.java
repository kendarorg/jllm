package org.kendar.llm.web.users;

import org.kendar.llm.web.commons.users.UserRepository;
import org.kendar.llm.web.commons.users.SkillRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Skills as files: global ones in {@code <webauth>/global-skills/<name>.md}, each user's own in
 * {@code <webauth>/user-skills/<userId>/<name>.md}. A file is markdown with a front matter holding {@code name} and
 * {@code description}; it is read on each use.
 */
public final class FileSkillRepository implements SkillRepository {

    private final Path global;
    private final Path users;

    public FileSkillRepository(Path webauth) {
        this.global = webauth.resolve("global-skills");
        this.users = webauth.resolve("user-skills");
    }

    @Override
    public List<Skill> global() {
        return list(global);
    }

    @Override
    public List<Skill> own(String userId) {
        return UserRepository.validId(userId) ? list(users.resolve(userId)) : List.of();
    }

    @Override
    public Optional<Skill> findGlobal(String name) {
        return read(global, name);
    }

    @Override
    public Optional<Skill> findOwn(String userId, String name) {
        return UserRepository.validId(userId) ? read(users.resolve(userId), name) : Optional.empty();
    }

    @Override
    public Skill writeGlobal(String name, String description, String content) {
        return write(global, name, description, content);
    }

    @Override
    public Skill writeOwn(String userId, String name, String description, String content) {
        if (!UserRepository.validId(userId)) throw new IllegalArgumentException("Invalid user id");
        return write(users.resolve(userId), name, description, content);
    }

    @Override
    public boolean deleteGlobal(String name) {
        return SkillRepository.validName(name) && JsonFiles.delete(global.resolve(name + ".md"));
    }

    @Override
    public boolean deleteOwn(String userId, String name) {
        return UserRepository.validId(userId) && SkillRepository.validName(name)
                && JsonFiles.delete(users.resolve(userId).resolve(name + ".md"));
    }

    @Override
    public void deleteAllOwn(String userId) {
        if (!UserRepository.validId(userId)) return;
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

    @Override
    public List<String> ownersOf(String name) {
        if (!SkillRepository.validName(name) || !Files.isDirectory(users)) return List.of();
        try (Stream<Path> s = Files.list(users)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(UserRepository::validId)
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
                    .filter(SkillRepository::validName)
                    .sorted()
                    .map(n -> read(dir, n))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Skill> read(Path dir, String name) {
        if (!SkillRepository.validName(name)) return Optional.empty();
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
            return Optional.of(new Skill(name, description, body));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Skill write(Path dir, String name, String description, String content) {
        if (!SkillRepository.validName(name)) throw new IllegalArgumentException("Invalid skill name: " + name);
        SkillRepository.checkSize(description, content);
        String text = "---\nname: " + name + "\ndescription: " + SkillRepository.cleanDescription(description)
                + "\n---\n" + (content == null ? "" : content);
        JsonFiles.writeString(dir.resolve(name + ".md"), text);
        return read(dir, name).orElseThrow();
    }
}

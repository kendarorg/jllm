package org.kendar.llm.harness.skills;

import org.kendar.llm.harness.settings.SkillSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The skills of a harness. A skill can be disabled at runtime; its text is read from disk on every load. */
public final class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    public record Skill(String name, String description, Path path) {
    }

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    /** Skills whose file is missing are logged and left out. @throws IllegalArgumentException on duplicate names */
    public SkillCatalog(List<SkillSettings> settings) {
        for (SkillSettings s : settings) {
            Path path = Path.of(s.path());
            if (!Files.isRegularFile(path)) {
                log.warn("Skill '{}' skipped: {} not found", s.name(), path);
                continue;
            }
            String description = s.description() != null ? s.description() : frontMatterDescription(path);
            if (skills.putIfAbsent(s.name(), new Skill(s.name(), description, path)) != null) {
                throw new IllegalArgumentException("Duplicate skill '" + s.name() + "'");
            }
        }
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public List<Skill> enabled() {
        return skills.values().stream().filter(s -> !disabled.contains(s.name())).toList();
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    public boolean isEnabled(String name) {
        return skills.containsKey(name) && !disabled.contains(name);
    }

    public void setEnabled(String name, boolean enabled) {
        if (!skills.containsKey(name)) throw new IllegalArgumentException("Unknown skill '" + name + "'");
        if (enabled) disabled.remove(name);
        else disabled.add(name);
    }

    /** @throws IllegalArgumentException if the skill is unknown or disabled */
    public String load(String name) throws IOException {
        if (!isEnabled(name)) {
            throw new IllegalArgumentException("No enabled skill '" + name + "'. Available: "
                    + enabled().stream().map(Skill::name).toList());
        }
        return Files.readString(skills.get(name).path(), StandardCharsets.UTF_8);
    }

    private static String frontMatterDescription(Path path) {
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.startsWith("description:")) return line.substring("description:".length()).strip();
            }
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", path, e.getMessage());
        }
        return "";
    }
}

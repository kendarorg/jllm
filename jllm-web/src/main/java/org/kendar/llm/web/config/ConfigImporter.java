package org.kendar.llm.web.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.harness.settings.VirtualConfigProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one-time import of a file configuration into a virtual provider ({@code jllm-web --import-config <dir>}). Reads
 * {@code settings.json}, {@code SYSTEM.md}, {@code APPEND_SYSTEM.md} and the instruction files from {@code <dir>} (or
 * {@code <dir>/.jllm}) and turns what names a file into stored text:
 * <ul>
 *     <li>the settings' {@code agents}: the prompt file is read into the inline prompt, the agent goes to the agent
 *     repository;</li>
 *     <li>the settings' {@code skills}: the skill file is read and stored as a global skill;</li>
 *     <li>the settings' {@code instructionFiles} and the default ones ({@code AGENTS.md}, {@code CLAUDE.md},
 *     {@code KENDAR.md}) found in the folder: stored as global instructions;</li>
 *     <li>{@code SYSTEM.md} and {@code APPEND_SYSTEM.md}: the system prompt instructions;</li>
 *     <li>the rest of {@code settings.json}: the settings document.</li>
 * </ul>
 * Entries of the same name are replaced. A relative path in the settings is read from {@code <dir>}.
 */
public final class ConfigImporter {

    /** What was imported, for the log. */
    public record Result(boolean settings, int agents, int skills, int instructions, boolean systemReplace,
                         boolean systemAppend) {
        @Override
        public String toString() {
            return "settings=" + settings + ", agents=" + agents + ", skills=" + skills + ", instructions=" + instructions
                    + ", system prompt replace=" + systemReplace + ", append=" + systemAppend;
        }
    }

    private static final ObjectMapper AGENTS = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Pattern DESCRIPTION = Pattern.compile("(?m)^description:\\s*(.+)$");

    private ConfigImporter() {
    }

    /**
     * @throws IllegalArgumentException when {@code dir} is not a folder or a skill or agent is invalid
     * @throws UncheckedIOException     when a file cannot be read
     */
    public static Result importDir(Path dir, VirtualConfigProvider provider, ObjectMapper mapper) {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a folder: " + dir);
        Path base = dir.toAbsolutePath().normalize();
        Path jllm = base.resolve(".jllm");
        boolean settingsDone = false;
        int agents = 0;
        int skills = 0;
        int instr = 0;
        List<String> instructionNames = new ArrayList<>();

        Path settingsFile = firstFile(base.resolve("settings.json"), jllm.resolve("settings.json"));
        if (settingsFile != null) {
            JsonNode tree;
            try {
                tree = mapper.readTree(settingsFile.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + settingsFile, e);
            }
            if (!(tree instanceof ObjectNode doc)) throw new IllegalArgumentException(settingsFile + " must be a JSON object");
            JsonNode agentNode = doc.remove("agents");
            if (agentNode instanceof ArrayNode list) {
                for (JsonNode a : list) {
                    ObjectNode copy = a.deepCopy();
                    if (copy.hasNonNull("promptFile")) {
                        String prompt = copy.path("prompt").asText("");
                        if (prompt.isBlank()) copy.put("prompt", read(resolve(base, copy.get("promptFile").asText())));
                        copy.remove("promptFile");
                    }
                    AgentSettings agent = AGENTS.convertValue(copy, AgentSettings.class);
                    provider.agents().write(agent);
                    agents++;
                }
            }
            JsonNode skillNode = doc.remove("skills");
            if (skillNode instanceof ArrayNode list) {
                SkillRepository repo = provider.skills();
                for (JsonNode s : list) {
                    String name = s.path("name").asText("");
                    if (!s.hasNonNull("path")) throw new IllegalArgumentException("Skill '" + name + "' has no path to import");
                    Path file = resolve(base, s.path("path").asText(""));
                    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Skill '" + name + "': " + file + " not found");
                    String content = read(file);
                    String description = s.hasNonNull("description") ? s.get("description").asText()
                            : frontMatterDescription(content);
                    repo.writeGlobal(name, description, content);
                    skills++;
                }
            }
            JsonNode filesNode = doc.remove("instructionFiles");
            if (filesNode instanceof ArrayNode list) {
                for (JsonNode f : list) {
                    String file = f.asText("");
                    if (file.startsWith("~")) continue; // the user's own home is not this server's configuration
                    Path p = resolve(base, file);
                    if (!Files.isRegularFile(p)) continue;
                    if (storeInstruction(provider, p)) {
                        instr++;
                        instructionNames.add(p.getFileName().toString());
                    }
                }
            }
            provider.putSettings(doc);
            settingsDone = true;
        }
        for (String name : HarnessSettings.DEFAULT_INSTRUCTION_FILES) {
            if (instructionNames.contains(name)) continue;
            Path p = firstFile(base.resolve(name), jllm.resolve(name));
            if (p != null && storeInstruction(provider, p)) instr++;
        }
        boolean replace = storeSystem(provider, firstFile(base.resolve("SYSTEM.md"), jllm.resolve("SYSTEM.md")),
                InstructionRepository.SYSTEM_REPLACE);
        boolean append = storeSystem(provider, firstFile(base.resolve("APPEND_SYSTEM.md"), jllm.resolve("APPEND_SYSTEM.md")),
                InstructionRepository.SYSTEM_APPEND);
        return new Result(settingsDone, agents, skills, instr, replace, append);
    }

    private static boolean storeInstruction(VirtualConfigProvider provider, Path file) {
        String text = read(file).strip();
        if (text.isEmpty()) return false;
        provider.instructions().write(InstructionRepository.Scope.GLOBAL, "", file.getFileName().toString(), text);
        return true;
    }

    private static boolean storeSystem(VirtualConfigProvider provider, Path file, String name) {
        if (file == null) return false;
        String text = read(file).strip();
        if (text.isEmpty()) return false;
        provider.instructions().write(InstructionRepository.Scope.SYSTEM, "", name, text);
        return true;
    }

    private static Path firstFile(Path... candidates) {
        for (Path p : candidates) if (Files.isRegularFile(p)) return p;
        return null;
    }

    /** A path of the settings, relative to the import folder; one that leaves the folder (also through a link) is refused. */
    private static Path resolve(Path base, String file) {
        Path p = base.resolve(file).normalize();
        boolean inside = p.startsWith(base);
        if (inside && Files.exists(p)) {
            try {
                inside = p.toRealPath().startsWith(base.toRealPath());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot resolve " + p, e);
            }
        }
        if (!inside) throw new IllegalArgumentException("'" + file + "' is outside the import folder " + base);
        return p;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static String frontMatterDescription(String content) {
        if (!content.startsWith("---")) return "";
        int end = content.indexOf("\n---", 3);
        if (end < 0) return "";
        Matcher m = DESCRIPTION.matcher(content.substring(0, end));
        return m.find() ? m.group(1).strip() : "";
    }
}

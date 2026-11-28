package org.kendar.llm.web.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.plugin.FilePluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.settings.AgentRepository;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.harness.settings.PathSettings;
import org.kendar.llm.harness.settings.SettingsLayer;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.settings.VirtualConfigProvider;
import org.kendar.llm.web.users.JsonFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The configuration of the web host as JSON files under a server-only directory (without a database): the directory is
 * outside {@code jllm.chats-dir} and no chat or working directory is ever read.
 * <pre>
 * settings.json                          the global settings document
 * instructions/system/{replace,append}.json
 * instructions/global/NAME.json
 * instructions/role/ROLE/NAME.json
 * instructions/user/USERID/NAME.json     each {"content": "..."}
 * agents/NAME.json                       one agent, inline prompt
 * plugin-data/                           the plugins' storage
 * </pre>
 * The skills are those of the {@link SkillRepository} given.
 */
public final class RemoteFileConfigProvider implements VirtualConfigProvider {

    private static final ObjectMapper AGENTS = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path dir;
    private final ObjectMapper mapper;
    private final SkillRepository skills;
    private final PluginStorages plugins;
    private final Instructions instructions = new Instructions();
    private final Agents agents = new Agents();

    public RemoteFileConfigProvider(Path dir, ObjectMapper mapper, SkillRepository skills) {
        this.dir = dir.toAbsolutePath().normalize();
        this.mapper = mapper;
        this.skills = skills;
        this.plugins = new FilePluginStorage(this.dir.resolve("plugin-data"), mapper);
    }

    public Path dir() {
        return dir;
    }

    @Override
    public SettingsSource settings() {
        return () -> {
            Path f = dir.resolve("settings.json");
            if (!Files.isRegularFile(f)) return List.of();
            try {
                return List.of(new SettingsLayer(SettingsLayer.Kind.GLOBAL, f.toString(), mapper.readTree(f.toFile())));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read settings " + f, e);
            }
        };
    }

    @Override
    public InstructionRepository instructions() {
        return instructions;
    }

    @Override
    public AgentRepository agents() {
        return agents;
    }

    @Override
    public SkillRepository skills() {
        return skills;
    }

    @Override
    public PluginStorages pluginStorages() {
        return plugins;
    }

    @Override
    public void putSettings(JsonNode tree) {
        PathSettings.check(tree, "settings.json");
        try {
            JsonFiles.writeString(dir.resolve("settings.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not serializable settings", e);
        }
    }

    @Override
    public JsonNode settingsDocument() {
        Path f = dir.resolve("settings.json");
        try {
            return Files.isRegularFile(f) ? mapper.readTree(f.toFile()) : mapper.createObjectNode();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings " + f, e);
        }
    }

    @Override
    public List<Path> serverPaths() {
        return List.of(dir);
    }

    @Override
    public String describe() {
        return "remote-file:" + dir;
    }

    private final class Instructions implements InstructionRepository {

        private Path folder(Scope scope, String owner) {
            Path base = dir.resolve("instructions").resolve(scope.name().toLowerCase(java.util.Locale.ROOT));
            return scope == Scope.ROLE || scope == Scope.USER ? base.resolve(owner) : base;
        }

        private boolean valid(Scope scope, String owner) {
            try {
                InstructionRepository.checkOwner(scope, owner);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @Override
        public List<Instruction> list(Scope scope, String owner) {
            String o = owner == null ? "" : owner;
            if (!valid(scope, o)) return List.of();
            Path f = folder(scope, o);
            if (!Files.isDirectory(f)) return List.of();
            List<Instruction> out = new ArrayList<>();
            try (Stream<Path> s = Files.list(f)) {
                for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    String name = p.getFileName().toString();
                    name = name.substring(0, name.length() - ".json".length());
                    if (!NAME.matcher(name).matches()) continue;
                    read(scope, o, name).ifPresent(out::add);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            out.sort(Comparator.comparing(Instruction::name));
            return out;
        }

        @Override
        public Optional<Instruction> find(Scope scope, String owner, String name) {
            String o = owner == null ? "" : owner;
            if (!valid(scope, o) || name == null || !NAME.matcher(name).matches()) return Optional.empty();
            return read(scope, o, name);
        }

        private Optional<Instruction> read(Scope scope, String owner, String name) {
            Path f = folder(scope, owner).resolve(name + ".json");
            if (!Files.isRegularFile(f)) return Optional.empty();
            try {
                return Optional.of(new Instruction(scope, owner, name, mapper.readTree(f.toFile()).path("content").asText("")));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + f, e);
            }
        }

        @Override
        public Instruction write(Scope scope, String owner, String name, String content) {
            InstructionRepository.check(scope, owner, name, content);
            String o = owner == null ? "" : owner;
            ObjectNode doc = mapper.createObjectNode().put("content", content == null ? "" : content);
            try {
                JsonFiles.writeString(folder(scope, o).resolve(name + ".json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
            return new Instruction(scope, o, name, content);
        }

        @Override
        public boolean delete(Scope scope, String owner, String name) {
            String o = owner == null ? "" : owner;
            if (!valid(scope, o) || name == null || !NAME.matcher(name).matches()) return false;
            return JsonFiles.delete(folder(scope, o).resolve(name + ".json"));
        }

        @Override
        public void deleteOwned(Scope scope, String owner) {
            String o = owner == null ? "" : owner;
            if (!valid(scope, o) || scope == Scope.SYSTEM || scope == Scope.GLOBAL) return;
            for (Instruction i : list(scope, o)) delete(scope, o, i.name());
            try {
                Files.deleteIfExists(folder(scope, o));
            } catch (IOException e) {
                // not empty or gone: nothing to do
            }
        }
    }

    private final class Agents implements AgentRepository {

        private Path file(String name) {
            return dir.resolve("agents").resolve(name + ".json");
        }

        @Override
        public List<AgentSettings> all() {
            Path f = dir.resolve("agents");
            if (!Files.isDirectory(f)) return List.of();
            List<AgentSettings> out = new ArrayList<>();
            try (Stream<Path> s = Files.list(f)) {
                for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    String name = p.getFileName().toString();
                    name = name.substring(0, name.length() - ".json".length());
                    if (NAME.matcher(name).matches()) find(name).ifPresent(out::add);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out;
        }

        @Override
        public Optional<AgentSettings> find(String name) {
            if (name == null || !NAME.matcher(name).matches() || !Files.isRegularFile(file(name))) return Optional.empty();
            try {
                return Optional.of(AGENTS.readValue(file(name).toFile(), AgentSettings.class));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file(name), e);
            }
        }

        @Override
        public AgentSettings write(AgentSettings agent) {
            AgentRepository.check(agent);
            try {
                JsonFiles.writeString(file(agent.name()), AGENTS.writerWithDefaultPrettyPrinter().writeValueAsString(agent));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Not serializable agent", e);
            }
            return agent;
        }

        @Override
        public boolean delete(String name) {
            return name != null && NAME.matcher(name).matches() && JsonFiles.delete(file(name));
        }
    }
}

package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.identity.HarnessIdentity;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.plugin.FilePluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.settings.AgentRepository;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.settings.ConfigProvider;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.harness.settings.SettingsLayer;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.settings.VirtualConfigProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The web harness takes its configuration from the virtual provider only: files in the chats directory (the place the
 * users write) change nothing, a provider that is not virtual stops the start, and settings that name files are refused.
 */
class VirtualConfigWebTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    /** A virtual provider in memory. */
    static class MemoryProvider implements VirtualConfigProvider {
        JsonNode settings = JsonNodeFactory.instance.objectNode();
        final List<InstructionRepository.Instruction> instructions = new ArrayList<>();
        final List<AgentSettings> agents = new ArrayList<>();
        List<Path> serverPaths = List.of();
        final PluginStorages plugins;

        MemoryProvider(Path pluginData) {
            plugins = new FilePluginStorage(pluginData, MAPPER);
        }

        @Override
        public void putSettings(JsonNode tree) {
            settings = tree;
        }

        @Override
        public JsonNode settingsDocument() {
            return settings;
        }

        @Override
        public List<Path> serverPaths() {
            return serverPaths;
        }

        @Override
        public String describe() {
            return "memory";
        }

        @Override
        public SettingsSource settings() {
            return () -> List.of(new SettingsLayer(SettingsLayer.Kind.GLOBAL, "memory", settings));
        }

        @Override
        public InstructionRepository instructions() {
            return new InstructionRepository() {
                public List<Instruction> list(Scope scope, String owner) {
                    return instructions.stream().filter(i -> i.scope() == scope).toList();
                }

                public Optional<Instruction> find(Scope scope, String owner, String name) {
                    return list(scope, owner).stream().filter(i -> i.name().equals(name)).findFirst();
                }

                public Instruction write(Scope scope, String owner, String name, String content) {
                    var i = new Instruction(scope, owner, name, content);
                    instructions.add(i);
                    return i;
                }

                public boolean delete(Scope scope, String owner, String name) {
                    return false;
                }

                public void deleteOwned(Scope scope, String owner) {
                }
            };
        }

        @Override
        public AgentRepository agents() {
            return new AgentRepository() {
                public List<AgentSettings> all() {
                    return agents;
                }

                public Optional<AgentSettings> find(String name) {
                    return agents.stream().filter(a -> a.name().equals(name)).findFirst();
                }

                public AgentSettings write(AgentSettings agent) {
                    agents.add(agent);
                    return agent;
                }

                public boolean delete(String name) {
                    return false;
                }
            };
        }

        @Override
        public SkillRepository skills() {
            return null;
        }

        @Override
        public PluginStorages pluginStorages() {
            return plugins;
        }
    }

    private static final class RecordingModel implements ModelClient {
        final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public ModelResponse complete(ModelRequest request) {
            requests.add(request);
            ArrayNode content = MAPPER.createArrayNode();
            content.addObject().put("type", "text").put("text", "ok");
            return new ModelResponse(content, "end_turn", new Usage(1, 1, 0, 0));
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            return "";
        }
    }

    private JllmProperties props(Path chats, String pluginDir) {
        return new JllmProperties("ollama", null, null, null, null, null, null, chats.toString(), null, null, null, null,
                null, null, null, null, null, null, null, pluginDir, null, null);
    }

    private AgentHarness build(JllmProperties p, ConfigProvider provider, ModelClient model) {
        return HarnessFactory.harness(p, model, new HarnessPricing(0, 0, 0, 0), MAPPER, HarnessIdentity.NONE, provider,
                null, null, null, null, null, new ServiceRegistry());
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void filesInTheChatsDirectoryChangeNothing() throws Exception {
        Path chats = dir.resolve("chats");
        // what a user (or a repository they unpacked) could put where the commands run
        write(chats.resolve(".jllm/settings.json"), "{\"smallModel\":true,\"skills\":[{\"name\":\"evil\","
                + "\"path\":\"" + chats.resolve("skills/evil/SKILL.md").toString().replace('\\', '/') + "\"}],"
                + "\"agents\":[{\"name\":\"evil-agent\",\"prompt\":\"EVIL AGENT\"}]}");
        write(chats.resolve("SYSTEM.md"), "EVIL REPLACE");
        write(chats.resolve("APPEND_SYSTEM.md"), "EVIL APPEND");
        write(chats.resolve("AGENTS.md"), "EVIL AGENTS");
        write(chats.resolve("CLAUDE.md"), "EVIL CLAUDE");
        write(chats.resolve("skills/evil/SKILL.md"), "EVIL SKILL");
        write(chats.resolve("chat1/AGENTS.md"), "EVIL CHAT AGENTS");
        write(chats.resolve("chat1/.jllm/settings.json"), "{\"instructionFiles\":[\"AGENTS.md\"]}");
        MemoryProvider provider = new MemoryProvider(dir.resolve("plugin-data"));
        provider.instructions.add(new InstructionRepository.Instruction(InstructionRepository.Scope.SYSTEM, "", "append",
                "PROVIDER APPEND"));
        provider.instructions.add(new InstructionRepository.Instruction(InstructionRepository.Scope.GLOBAL, "", "house.md",
                "HOUSE RULE"));
        RecordingModel model = new RecordingModel();

        AgentHarness harness = build(props(chats, null), provider, model);
        String chat = harness.sessions().create("t").chatId();
        harness.start(chat, "hello");
        for (int i = 0; i < 200 && (harness.isRunning(chat) || model.requests.isEmpty()); i++) Thread.sleep(25);

        assertFalse(model.requests.isEmpty());
        String system = model.requests.get(0).pinnedSystem() + "\n" + model.requests.get(0).extraSystem();
        assertTrue(system.contains("PROVIDER APPEND"), system);
        assertTrue(system.contains("HOUSE RULE"), system);
        assertFalse(system.contains("EVIL"), system);
        assertEquals(List.of(), harness.skills().all(), "no skill of a file under the chats");
        assertEquals(List.of(), harness.contentWarnings());
        assertFalse(model.requests.get(0).messages().toString().contains("EVIL"));
    }

    @Test
    void theProvidersAgentsAndSettingsApply() throws Exception {
        MemoryProvider provider = new MemoryProvider(dir.resolve("plugin-data"));
        provider.settings = MAPPER.readTree("{\"agents\":[{\"name\":\"from-settings\",\"prompt\":\"a\"},"
                + "{\"name\":\"both\",\"prompt\":\"old\"}]}");
        provider.agents.add(new AgentSettings("both", null, null, "new", null, null, null, null, null, null, null));
        var merged = HarnessFactory.mergeAgents(MAPPER.convertValue(provider.settings.path("agents"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, AgentSettings.class)), provider.agents);
        assertEquals(List.of("from-settings", "both"), merged.stream().map(AgentSettings::name).toList());
        assertEquals("new", merged.get(1).prompt(), "the provider's agent replaces the one of the same name");
        build(props(dir.resolve("chats"), null), provider, new RecordingModel());
    }

    @Test
    void startupFailsWithoutAVirtualProvider() {
        JllmProperties p = props(dir.resolve("c1"), null);
        var none = assertThrows(IllegalStateException.class, () -> build(p, null, new RecordingModel()));
        assertTrue(none.getMessage().contains("virtual configuration"), none.getMessage());
        MemoryProvider virtual = new MemoryProvider(dir.resolve("plugin-data"));
        ConfigProvider fileLike = new ConfigProvider() {
            public SettingsSource settings() {
                return virtual.settings();
            }

            public InstructionRepository instructions() {
                return virtual.instructions();
            }

            public AgentRepository agents() {
                return virtual.agents();
            }

            public SkillRepository skills() {
                return null;
            }

            public PluginStorages pluginStorages() {
                return virtual.pluginStorages();
            }
        };
        var plain = assertThrows(IllegalStateException.class, () -> build(p, fileLike, new RecordingModel()));
        assertTrue(plain.getMessage().contains("virtual configuration"), plain.getMessage());
    }

    @Test
    void settingsThatNameFilesAreRejectedOnLoad() throws Exception {
        for (String json : List.of("{\"instructionFiles\":[\"AGENTS.md\"]}",
                "{\"agents\":[{\"name\":\"a\",\"promptFile\":\"p.md\"}]}",
                "{\"skills\":[{\"name\":\"s\",\"path\":\"s.md\"}]}")) {
            MemoryProvider provider = new MemoryProvider(dir.resolve("plugin-data"));
            provider.settings = MAPPER.readTree(json);
            var e = assertThrows(IllegalArgumentException.class,
                    () -> build(props(dir.resolve("chats-" + Math.abs(json.hashCode())), null), provider, new RecordingModel()), json);
            assertTrue(e.getMessage().contains("name files"), e.getMessage());
        }
    }

    @Test
    void aStoredProjectLayerIsRefused() {
        MemoryProvider provider = new MemoryProvider(dir.resolve("plugin-data")) {
            @Override
            public SettingsSource settings() {
                return () -> List.of(new SettingsLayer(SettingsLayer.Kind.PROJECT, "x", MAPPER.createObjectNode()));
            }
        };
        assertThrows(IllegalStateException.class, () -> build(props(dir.resolve("chats-p"), null), provider, new RecordingModel()));
    }

    @Test
    void startupFailsWhenTheProviderOrThePluginFolderIsInsideTheChatsDirectory() {
        Path chats = dir.resolve("chats-g");
        MemoryProvider provider = new MemoryProvider(dir.resolve("plugin-data"));
        provider.serverPaths = List.of(chats.resolve("config"));
        var e = assertThrows(IllegalStateException.class, () -> build(props(chats, null), provider, new RecordingModel()));
        assertTrue(e.getMessage().contains("jllm.chats-dir"), e.getMessage());
        MemoryProvider other = new MemoryProvider(dir.resolve("plugin-data"));
        var p = assertThrows(IllegalStateException.class,
                () -> build(props(chats, chats.resolve("plugins").toString()), other, new RecordingModel()));
        assertTrue(p.getMessage().contains("jllm.plugin-dir"), p.getMessage());
    }
}

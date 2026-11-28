package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Layers kept outside files (e.g. a database) get the same merge and the same project rules as the files. */
class SettingsSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path project;

    private final List<String> warnings = new ArrayList<>();

    /** Answers kept in memory, as a database would keep them. */
    private static final class MemoryTrust implements FolderTrust {
        final Map<String, Boolean> answers = new HashMap<>();

        @Override
        public Boolean lookup(Path projectDir, String server, JsonNode definition) {
            return answers.get(FolderTrust.projectKey(projectDir) + "|" + server + "|"
                    + FolderTrust.fingerprint(definition));
        }

        @Override
        public void remember(Path projectDir, String server, JsonNode definition, boolean trusted) {
            answers.put(FolderTrust.projectKey(projectDir) + "|" + server + "|"
                    + FolderTrust.fingerprint(definition), trusted);
        }

        @Override
        public java.util.Optional<Record> record(Path projectDir) {
            return java.util.Optional.empty();
        }

        @Override
        public void trust(Path projectDir, java.util.Set<String> loosenings) {
        }

        @Override
        public void approveLoosening(Path projectDir, String key) {
        }
    }

    private static SettingsLayer layer(SettingsLayer.Kind kind, String json) throws Exception {
        return new SettingsLayer(kind, "db:" + kind, MAPPER.readTree(json));
    }

    @Test
    void layersMergeLaterWinsAndDenialsUnite() throws Exception {
        List<SettingsLayer> layers = List.of(
                layer(SettingsLayer.Kind.GLOBAL, "{\"smallModel\":true,\"permissions\":{\"deny\":[\"a\"]}}"),
                layer(SettingsLayer.Kind.PROJECT, "{\"smallModel\":false,\"permissions\":{\"deny\":[\"b\"]}}"));
        HarnessSettings s = HarnessSettingsLoader.loadLayered(() -> layers, project, k -> null, FolderTrust.Prompt.NONE,
                new MemoryTrust(), warnings::add,
                new org.kendar.llm.harness.safety.ContentWarnings());
        assertFalse(s.isSmallModel());
        assertEquals(List.of("a", "b"), s.permissions().deny());
    }

    @Test
    void projectStdioServerNeedsTrustFromTheGivenStore() throws Exception {
        String server = "{\"mcpServers\":[{\"name\":\"x\",\"command\":\"run-me\"}]}";
        List<SettingsLayer> layers = List.of(layer(SettingsLayer.Kind.PROJECT, server));
        MemoryTrust trust = new MemoryTrust();

        HarnessSettings refused = HarnessSettingsLoader.loadLayered(() -> layers, project, k -> null,
                FolderTrust.Prompt.NONE, trust, warnings::add,
                new org.kendar.llm.harness.safety.ContentWarnings());
        assertTrue(refused.mcpServers() == null || refused.mcpServers().isEmpty());

        HarnessSettings asked = HarnessSettingsLoader.loadLayered(() -> layers, project, k -> null,
                (dir, name, line) -> true, trust, warnings::add,
                new org.kendar.llm.harness.safety.ContentWarnings());
        assertEquals(1, asked.mcpServers().size());
        assertEquals(1, trust.answers.size(), "the answer is remembered in the given store");

        HarnessSettings remembered = HarnessSettingsLoader.loadLayered(() -> layers, project, k -> null,
                FolderTrust.Prompt.NONE, trust, warnings::add,
                new org.kendar.llm.harness.safety.ContentWarnings());
        assertEquals(1, remembered.mcpServers().size());
    }

    @Test
    void sameServerInAnExplicitLayerNeedsNoTrust() throws Exception {
        String server = "{\"mcpServers\":[{\"name\":\"x\",\"command\":\"run-me\"}]}";
        List<SettingsLayer> layers = List.of(layer(SettingsLayer.Kind.EXPLICIT, server));
        HarnessSettings s = HarnessSettingsLoader.loadLayered(() -> layers, project, k -> null,
                FolderTrust.Prompt.NONE, new MemoryTrust(), warnings::add,
                new org.kendar.llm.harness.safety.ContentWarnings());
        assertEquals(1, s.mcpServers().size());
    }

    @Test
    void projectLayerWithoutProjectIsRefused() throws Exception {
        List<SettingsLayer> layers = List.of(layer(SettingsLayer.Kind.PROJECT, "{\"smallModel\":true}"));
        assertThrows(IllegalArgumentException.class, () -> HarnessSettingsLoader.loadLayered(() -> layers, null,
                k -> null, FolderTrust.Prompt.NONE, new MemoryTrust(), warnings::add,
                new org.kendar.llm.harness.safety.ContentWarnings()));
    }

    @Test
    void nonObjectLayerIsIgnoredWithItsOrigin() throws Exception {
        List<SettingsLayer> layers = List.of(layer(SettingsLayer.Kind.GLOBAL, "[1]"),
                layer(SettingsLayer.Kind.EXPLICIT, "{\"smallModel\":true}"));
        HarnessSettings s = HarnessSettingsLoader.loadLayered(() -> layers, project, k -> null,
                FolderTrust.Prompt.NONE, new MemoryTrust(), warnings::add,
                new org.kendar.llm.harness.safety.ContentWarnings());
        assertTrue(s.isSmallModel());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("db:GLOBAL") && w.contains("ignored")), warnings.toString());
    }
}

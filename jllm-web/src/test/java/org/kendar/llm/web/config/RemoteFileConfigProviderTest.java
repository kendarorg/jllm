package org.kendar.llm.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.settings.InstructionRepository.Scope;
import org.kendar.llm.web.users.FileSkillRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteFileConfigProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private RemoteFileConfigProvider provider() {
        return new RemoteFileConfigProvider(dir.resolve("config"), MAPPER, new FileSkillRepository(dir.resolve("webauth")));
    }

    @Test
    void settingsAreAGlobalLayerAndPathsAreRefused() throws Exception {
        var p = provider();
        assertTrue(p.settings().layers().isEmpty());
        assertTrue(p.settingsDocument().isObject());
        p.putSettings(MAPPER.readTree("{\"smallModel\":true}"));
        var layers = p.settings().layers();
        assertEquals(1, layers.size());
        assertTrue(layers.get(0).tree().path("smallModel").asBoolean());
        assertTrue(Files.isRegularFile(dir.resolve("config/settings.json")));
        assertThrows(IllegalArgumentException.class, () -> p.putSettings(MAPPER.readTree("{\"instructionFiles\":[\"A.md\"]}")));
        assertTrue(p.settingsDocument().path("smallModel").asBoolean(), "a refused document changes nothing");
    }

    @Test
    void instructionsAreKeptPerScopeAndOwner() {
        var in = provider().instructions();
        in.write(Scope.SYSTEM, "", "replace", "You are X.");
        in.write(Scope.SYSTEM, "", "append", "Be kind.");
        in.write(Scope.GLOBAL, "", "AGENTS.md", "Rule.");
        in.write(Scope.ROLE, "dev", "style", "Short.");
        in.write(Scope.USER, "ann", "me", "Ann.");
        assertEquals("You are X.", in.systemPromptReplace().orElseThrow());
        assertEquals("Be kind.", in.systemPromptAppend());
        assertEquals("Instructions from AGENTS.md:\nRule.", in.renderGlobal());
        assertTrue(in.renderFor("dev", "ann").contains("Short."));
        assertEquals("", in.renderFor("ops", "bob"));
        assertEquals(List.of("style"), in.list(Scope.ROLE, "dev").stream().map(i -> i.name()).toList());
        assertTrue(in.list(Scope.ROLE, "ops").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> in.write(Scope.GLOBAL, "", "../../escape", "x"));
        assertThrows(IllegalArgumentException.class, () -> in.write(Scope.ROLE, "../x", "a", "x"));
        assertTrue(in.find(Scope.GLOBAL, "", "..").isEmpty());
        assertTrue(in.delete(Scope.GLOBAL, "", "AGENTS.md"));
        assertFalse(in.delete(Scope.GLOBAL, "", "AGENTS.md"));
        in.deleteOwned(Scope.ROLE, "dev");
        assertTrue(in.list(Scope.ROLE, "dev").isEmpty());
        assertEquals(1, in.list(Scope.USER, "ann").size());
        // a second provider on the same folder sees the same files
        assertEquals("Be kind.", provider().instructions().systemPromptAppend());
    }

    @Test
    void agentsAreInlineOnly() {
        var agents = provider().agents();
        agents.write(new AgentSettings("reviewer", "Reviews", List.of("read_file"), "Review it.", null, null, null, true,
                null, null, null));
        assertEquals("Review it.", agents.find("reviewer").orElseThrow().prompt());
        assertEquals(1, agents.all().size());
        assertThrows(IllegalArgumentException.class, () -> agents.write(
                new AgentSettings("bad", null, null, null, "/etc/passwd", null, null, null, null, null, null)));
        assertTrue(agents.find("bad").isEmpty());
        assertTrue(agents.find("../x").isEmpty());
        assertTrue(agents.delete("reviewer"));
        assertTrue(agents.all().isEmpty());
    }

    @Test
    void theProviderKeepsItsFilesUnderItsFolderOnly() {
        var p = provider();
        p.instructions().write(Scope.GLOBAL, "", "a", "x");
        p.agents().write(new AgentSettings("ag", null, null, "p", null, null, null, null, null, null, null));
        assertEquals(List.of(dir.resolve("config").toAbsolutePath().normalize()), p.serverPaths());
        assertNotNull(p.pluginStorages().forPlugin("x"));
        try (var s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> assertTrue(f.startsWith(dir.resolve("config")), f.toString()));
        } catch (java.io.IOException e) {
            fail(e);
        }
    }
}

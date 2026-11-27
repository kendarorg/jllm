package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.SettingsLayer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The file provider of the command line reads the project only when it is built for a trusted folder. */
class FileConfigProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;
    private String oldHome;

    @BeforeEach
    void home() throws Exception {
        oldHome = System.getProperty("jllm.home");
        System.setProperty("jllm.home", Files.createDirectories(dir.resolve("home")).toString());
    }

    @AfterEach
    void restore() {
        if (oldHome == null) System.clearProperty("jllm.home");
        else System.setProperty("jllm.home", oldHome);
    }

    private Path project() throws Exception {
        Path p = Files.createDirectories(dir.resolve("project/.jllm"));
        Files.writeString(p.resolve("settings.json"), "{\"smallModel\":true}");
        Files.writeString(p.resolve("SYSTEM.md"), "Project system.");
        Files.writeString(p.resolve("APPEND_SYSTEM.md"), "Project append.");
        return p.getParent();
    }

    @Test
    void theUserOnlyProviderReadsNothingOfTheProject() throws Exception {
        project();
        var provider = FileConfigProvider.userOnly(null, MAPPER);
        assertTrue(provider.settings().layers().isEmpty());
        assertTrue(provider.instructions().systemPromptReplace().isEmpty());
        assertEquals("", provider.instructions().systemPromptAppend());
    }

    @Test
    void theTrustedProviderReadsTheProjectFiles() throws Exception {
        Path project = project();
        var provider = FileConfigProvider.trusted(project, null, true, MAPPER);
        var layers = provider.settings().layers();
        assertEquals(1, layers.size());
        assertEquals(SettingsLayer.Kind.PROJECT, layers.get(0).kind());
        assertEquals("Project system.", provider.instructions().systemPromptReplace().orElseThrow());
        assertEquals("Project append.", provider.instructions().systemPromptAppend());
    }

    @Test
    void theProjectSystemPromptNeedsItsOwnApproval() throws Exception {
        Path project = project();
        var provider = FileConfigProvider.trusted(project, null, false, MAPPER);
        assertTrue(provider.instructions().systemPromptReplace().isEmpty(), "SYSTEM.md replaces the prompt: approved separately");
        assertEquals("Project append.", provider.instructions().systemPromptAppend());
        assertThrows(IllegalArgumentException.class, () -> FileConfigProvider.trusted(null, null, true, MAPPER));
    }

    @Test
    void theFilesAreEditedByHand() throws Exception {
        var provider = FileConfigProvider.trusted(project(), null, true, MAPPER);
        assertThrows(UnsupportedOperationException.class, () -> provider.instructions().write(
                org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "a", "b"));
        assertTrue(provider.agents().all().isEmpty());
        assertNotNull(provider.skills());
        assertNotNull(provider.pluginStorages());
    }
}

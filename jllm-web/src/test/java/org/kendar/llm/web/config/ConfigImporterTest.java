package org.kendar.llm.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.InstructionRepository.Scope;
import org.kendar.llm.web.users.FileSkillRepository;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigImporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    static void write(Path f, String text) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    /** A file configuration the way the CLI keeps it. */
    static Path fileConfiguration(Path src) throws Exception {
        write(src.resolve("settings.json"), "{\"smallModel\":true,"
                + "\"agents\":[{\"name\":\"reviewer\",\"description\":\"d\",\"promptFile\":\"prompts/reviewer.md\"},"
                + "{\"name\":\"inline\",\"prompt\":\"Inline.\"}],"
                + "\"skills\":[{\"name\":\"pdf\",\"path\":\"skills/pdf.md\"}],"
                + "\"instructionFiles\":[\"RULES.md\",\"~/HOME.md\"]}");
        write(src.resolve("prompts/reviewer.md"), "Review carefully.");
        write(src.resolve("skills/pdf.md"), "---\ndescription: Read PDFs\n---\nUse pdftotext.");
        write(src.resolve("RULES.md"), "Rule one.");
        write(src.resolve("AGENTS.md"), "Agent rules.");
        write(src.resolve("SYSTEM.md"), "You are X.");
        write(src.resolve("APPEND_SYSTEM.md"), "Be kind.");
        return src;
    }

    @Test
    void aFileConfigurationBecomesStoredText() throws Exception {
        Path src = fileConfiguration(dir.resolve("src"));
        var provider = new RemoteFileConfigProvider(dir.resolve("config"), MAPPER, new FileSkillRepository(dir.resolve("webauth")));

        var r = ConfigImporter.importDir(src, provider, MAPPER);

        assertEquals(2, r.agents());
        assertEquals(1, r.skills());
        assertEquals(2, r.instructions(), r.toString());
        assertTrue(r.systemReplace() && r.systemAppend() && r.settings());
        assertEquals("Review carefully.", provider.agents().find("reviewer").orElseThrow().prompt());
        assertNull(provider.agents().find("reviewer").orElseThrow().promptFile());
        assertEquals("Inline.", provider.agents().find("inline").orElseThrow().prompt());
        var skill = provider.skills().findGlobal("pdf").orElseThrow();
        assertEquals("Read PDFs", skill.description());
        assertTrue(skill.content().contains("Use pdftotext."));
        assertEquals("Rule one.", provider.instructions().find(Scope.GLOBAL, "", "RULES.md").orElseThrow().content());
        assertEquals("Agent rules.", provider.instructions().find(Scope.GLOBAL, "", "AGENTS.md").orElseThrow().content());
        assertTrue(provider.instructions().find(Scope.GLOBAL, "", "HOME.md").isEmpty(), "the user's home is not imported");
        assertEquals("You are X.", provider.instructions().systemPromptReplace().orElseThrow());
        assertEquals("Be kind.", provider.instructions().systemPromptAppend());
        var settings = provider.settingsDocument();
        assertTrue(settings.path("smallModel").asBoolean());
        assertFalse(settings.has("agents") || settings.has("skills") || settings.has("instructionFiles"),
                "nothing in the stored settings names a file: " + settings);
        // importing again replaces, it does not duplicate
        ConfigImporter.importDir(src, provider, MAPPER);
        assertEquals(2, provider.agents().all().size());
    }

    @Test
    void theJllmFolderOfAProjectIsReadToo() throws Exception {
        Path src = dir.resolve("project");
        write(src.resolve(".jllm/settings.json"), "{\"smallModel\":true}");
        write(src.resolve(".jllm/SYSTEM.md"), "Sys.");
        var provider = new RemoteFileConfigProvider(dir.resolve("config"), MAPPER, new FileSkillRepository(dir.resolve("webauth")));
        var r = ConfigImporter.importDir(src, provider, MAPPER);
        assertTrue(r.settings() && r.systemReplace());
        assertEquals("Sys.", provider.instructions().systemPromptReplace().orElseThrow());
    }

    @Test
    void skillsAreStoredWithTheirContentAndNoPath() throws Exception {
        Path src = dir.resolve("imp");
        write(src.resolve("settings.json"), "{\"skills\":[{\"name\":\"x\",\"description\":\"Does x\",\"path\":\"skills/x/SKILL.md\"},"
                + "{\"name\":\"y\",\"path\":\"skills/y/SKILL.md\"}]}");
        write(src.resolve("skills/x/SKILL.md"), "Do the x thing.");
        write(src.resolve("skills/y/SKILL.md"), "---\ndescription: Why y\n---\nBody y.");
        var provider = new RemoteFileConfigProvider(dir.resolve("config"), MAPPER, new FileSkillRepository(dir.resolve("webauth")));
        assertEquals(2, ConfigImporter.importDir(src, provider, MAPPER).skills());
        var x = provider.skills().findGlobal("x").orElseThrow();
        assertEquals("Do the x thing.", x.content());
        assertEquals("Does x", x.description());
        assertEquals("Why y", provider.skills().findGlobal("y").orElseThrow().description());
        assertFalse(provider.settingsDocument().has("skills"), "the stored settings carry no skill path");
        assertFalse(Files.readString(dir.resolve("config/settings.json")).contains("SKILL.md"));
    }

    @Test
    void pathsThatLeaveTheImportFolderAreRefused() throws Exception {
        Path outside = dir.resolve("secret.md");
        write(outside, "secret");
        var provider = new RemoteFileConfigProvider(dir.resolve("config"), MAPPER, new FileSkillRepository(dir.resolve("webauth")));
        for (String settings : java.util.List.of(
                "{\"skills\":[{\"name\":\"s\",\"path\":\"../secret.md\"}]}",
                "{\"skills\":[{\"name\":\"s\",\"path\":\"" + outside.toString().replace("\\", "/") + "\"}]}",
                "{\"agents\":[{\"name\":\"a\",\"promptFile\":\"../secret.md\"}]}",
                "{\"instructionFiles\":[\"../secret.md\"]}")) {
            Path src = Files.createDirectories(dir.resolve("imp" + Math.abs(settings.hashCode())));
            write(src.resolve("settings.json"), settings);
            var e = assertThrows(IllegalArgumentException.class, () -> ConfigImporter.importDir(src, provider, MAPPER), settings);
            assertTrue(e.getMessage().contains("outside the import folder"), e.getMessage());
        }
        assertTrue(provider.skills().findGlobal("s").isEmpty());
        assertTrue(provider.agents().all().isEmpty());
    }

    @Test
    void aSymbolicLinkOutOfTheImportFolderIsRefused() throws Exception {
        Path outside = dir.resolve("secret.md");
        write(outside, "secret");
        Path src = Files.createDirectories(dir.resolve("linked"));
        write(src.resolve("settings.json"), "{\"skills\":[{\"name\":\"s\",\"path\":\"skills/SKILL.md\"}]}");
        Files.createDirectories(src.resolve("skills"));
        try {
            Files.createSymbolicLink(src.resolve("skills/SKILL.md"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here: " + e);
        }
        var provider = new RemoteFileConfigProvider(dir.resolve("config"), MAPPER, new FileSkillRepository(dir.resolve("webauth")));
        assertThrows(IllegalArgumentException.class, () -> ConfigImporter.importDir(src, provider, MAPPER));
        assertTrue(provider.skills().findGlobal("s").isEmpty());
    }

    @Test
    void aMissingFolderIsRefused() {
        var provider = new RemoteFileConfigProvider(dir.resolve("config"), MAPPER, new FileSkillRepository(dir.resolve("webauth")));
        assertThrows(IllegalArgumentException.class, () -> ConfigImporter.importDir(dir.resolve("nope"), provider, MAPPER));
    }
}

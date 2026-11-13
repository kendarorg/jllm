package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.instructions.SystemPromptFiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LayeredSettingsTest {

    @TempDir
    Path tmp;

    private void write(Path f, String s) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, s);
    }

    @Test
    void laterLayerWinsAndObjectsMergePerKey() throws Exception {
        Path home = tmp.resolve("home");
        Path project = tmp.resolve("proj");
        System.setProperty("jllm.home", home.toString());
        try {
            write(home.resolve(".jllm/settings.json"),
                    "{\"smallModel\":true,\"context\":{\"maxTokens\":1000,\"keepRecentTurns\":3},\"instructionFiles\":[\"a\"]}");
            write(project.resolve(".jllm/settings.json"), "{\"context\":{\"maxTokens\":2000},\"instructionFiles\":[\"b\"]}");
            write(tmp.resolve("explicit.json"), "{\"context\":{\"keepRecentTurns\":9}}");
            HarnessSettings s = load(tmp.resolve("explicit.json"), project);
            assertTrue(s.isSmallModel());
            assertEquals(2000, s.context().maxTokens());
            assertEquals(9, s.context().keepRecentTurns());
            assertEquals(java.util.List.of("b"), s.instructionFiles());
            assertEquals(1000, load(null, tmp.resolve("none")).context().maxTokens());
            assertThrows(java.io.UncheckedIOException.class,
                    () -> load(tmp.resolve("missing.json"), project));
        } finally {
            System.clearProperty("jllm.home");
        }
    }

    @Test
    void systemPromptFilesReplaceAndAppend() throws Exception {
        Path home = tmp.resolve("home");
        Path project = tmp.resolve("proj");
        System.setProperty("jllm.home", home.toString());
        try {
            write(home.resolve(".jllm/SYSTEM.md"), "global");
            write(home.resolve(".jllm/APPEND_SYSTEM.md"), "ga");
            assertEquals("global", SystemPromptFiles.load(project).replace());
            write(project.resolve(".jllm/SYSTEM.md"), "project");
            write(project.resolve(".jllm/APPEND_SYSTEM.md"), "pa");
            SystemPromptFiles f = SystemPromptFiles.load(project);
            assertEquals("project", f.replace());
            assertEquals("ga\n\npa", f.append());
        } finally {
            System.clearProperty("jllm.home");
        }
    }

    private static HarnessSettings load(Path explicit, Path project) {
        return HarnessSettings.loadLayered(new FileSettingsSource(explicit, project), project, k -> null, null,
                new FileProjectMcpTrust(project.resolve("trust.json")), w -> { });
    }
}

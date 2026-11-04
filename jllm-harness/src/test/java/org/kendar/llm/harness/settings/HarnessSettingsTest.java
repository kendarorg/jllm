package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessSettingsTest {

    @TempDir
    Path dir;

    private Path write(String json) throws Exception {
        return Files.writeString(dir.resolve("settings.json"), json);
    }

    @Test
    void loadsAndExpandsEnvVariables() throws Exception {
        Path f = write("""
                {"mcpServers":[{"name":"m","url":"http://h/mcp","headers":{"Authorization":"Bearer ${TOKEN}"}}],
                 "restApis":[{"name":"r","openApiUrl":"http://h/spec","allowedPaths":["GET /x"]}],
                 "unknown":1}""");
        HarnessSettings s = HarnessSettings.load(f, Map.of("TOKEN", "abc")::get);

        assertEquals("Bearer abc", s.mcpServers().get(0).headers().get("Authorization"));
        assertTrue(s.mcpServers().get(0).active());
        assertEquals("Rest/r", s.restApis().get(0).effectiveGroup());
        assertEquals(1, s.restApis().get(0).allowedPaths().size());
    }

    @Test
    void executionSettingIsRead() throws Exception {
        assertTrue(HarnessSettings.load(write("{}"), k -> null).serializeModelCalls());
        assertTrue(HarnessSettings.load(write("{\"execution\":{}}"), k -> null).serializeModelCalls());
        assertEquals(false, HarnessSettings.load(write("{\"execution\":{\"serializeModelCalls\":false}}"), k -> null)
                .serializeModelCalls());
    }

    @Test
    void smallModelIsReadAndKeptByTheWithers() throws Exception {
        assertFalse(HarnessSettings.load(write("{}"), k -> null).isSmallModel());
        HarnessSettings s = HarnessSettings.load(write("{\"smallModel\":true}"), k -> null);
        assertTrue(s.isSmallModel());
        assertTrue(s.withMaxTokens(1000).withContextStrategy("mnemonic").isSmallModel());
        assertTrue(HarnessSettings.empty().withSmallModel(true).isSmallModel());
    }

    @Test
    void missingVariableFailsWithItsName() throws Exception {
        Path f = write("{\"mcpServers\":[{\"name\":\"m\",\"url\":\"${NOPE}\"}]}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> HarnessSettings.load(f, k -> null));
        assertTrue(e.getMessage().contains("NOPE"));
    }
}

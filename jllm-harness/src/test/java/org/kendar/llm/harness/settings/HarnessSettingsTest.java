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
    void maxParallelIsRead() throws Exception {
        assertEquals(1, HarnessSettings.load(write("{}"), k -> null).maxParallel());
        assertEquals(1, HarnessSettings.load(write("{\"execution\":{}}"), k -> null).maxParallel());
        assertEquals(3, HarnessSettings.load(write("{\"execution\":{\"maxParallel\":3}}"), k -> null).maxParallel());
        assertEquals(1, HarnessSettings.load(write("{\"execution\":{\"maxParallel\":0}}"), k -> null).maxParallel());
        assertEquals(-1, HarnessSettings.load(write("{\"execution\":{\"maxParallel\":-1}}"), k -> null).maxParallel());
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

    @Test
    void scopeThresholdsOverrideTheDefaults() throws Exception {
        HarnessSettings s = HarnessSettings.load(write(
                "{\"context\":{\"strategy\":\"scope\",\"scope\":{\"offloadChars\":5000,\"safetyPercent\":5}}}"), k -> null);
        var d = org.kendar.llm.harness.context.scope.ScopeStrategy.Options.defaults();
        var o = s.scopeOptions(d);
        assertEquals(5000, o.offloadChars());
        assertEquals(5, o.safetyPercent());
        assertEquals(d.previewChars(), o.previewChars());
        assertEquals(d, HarnessSettings.empty().scopeOptions(d));
        assertEquals(org.kendar.llm.harness.context.ContextStrategyKind.SCOPE,
                s.applyTo(org.kendar.llm.harness.HarnessConfig.defaults()).contextStrategy());
    }

    @Test
    void providerOrderIsRead() throws Exception {
        HarnessSettings s = HarnessSettings.load(write(
                "{\"providers\":[{\"name\":\"a\",\"type\":\"openai\",\"order\":2}]}"), k -> null);
        assertEquals(2, s.providers().get(0).order());
    }
}

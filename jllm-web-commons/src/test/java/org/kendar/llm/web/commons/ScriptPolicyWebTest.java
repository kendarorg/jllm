package org.kendar.llm.web.commons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The web host's default for the run_js isolated profile is the untrusted sandbox policy. */
class ScriptPolicyWebTest {

    @TempDir
    Path dir;

    private HarnessSettings load(String json) throws Exception {
        return HarnessSettingsLoader.load(Files.writeString(dir.resolve("s.json"), json));
    }

    @Test
    void anEnabledJsPluginGetsTheUntrustedPolicy() throws Exception {
        HarnessSettings s = HarnessConfiguration.withUntrustedScriptPolicy(load("{\"plugins\":{\"js\":{\"enabled\":true}}}"));

        assertEquals("untrusted", s.plugins().path("js").path("settings").path("policy").asText());
        assertTrue(s.plugins().path("js").path("enabled").asBoolean(), "still enabled");
    }

    @Test
    void anExplicitPolicyIsKeptAndOtherJsSettingsSurvive() throws Exception {
        HarnessSettings s = HarnessConfiguration.withUntrustedScriptPolicy(load(
                "{\"plugins\":{\"js\":{\"enabled\":true,\"settings\":{\"policy\":\"isolated\",\"maxWallSeconds\":60}}}}"));

        assertEquals("isolated", s.plugins().path("js").path("settings").path("policy").asText());
        assertEquals(60, s.plugins().path("js").path("settings").path("maxWallSeconds").asInt());
    }

    @Test
    void otherSettingsOfTheJsPluginAreKeptWhenTheDefaultIsAdded() throws Exception {
        HarnessSettings s = HarnessConfiguration.withUntrustedScriptPolicy(load(
                "{\"plugins\":{\"js\":{\"enabled\":true,\"settings\":{\"maxWallSeconds\":60}}}}"));

        assertEquals("untrusted", s.plugins().path("js").path("settings").path("policy").asText());
        assertEquals(60, s.plugins().path("js").path("settings").path("maxWallSeconds").asInt());
    }

    @Test
    void aDisabledOrUnlistedPluginIsLeftAlone() throws Exception {
        HarnessSettings off = HarnessConfiguration.withUntrustedScriptPolicy(
                load("{\"plugins\":{\"js\":{\"enabled\":false}}}"));
        HarnessSettings none = HarnessConfiguration.withUntrustedScriptPolicy(load("{}"));

        assertFalse(off.plugins().path("js").path("settings").has("policy"));
        assertTrue(none.plugins() == null || none.plugins().isMissingNode() || !none.plugins().has("js"));
    }
}

package org.kendar.llm.cli;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Command line flags over the settings files and the configuration. */
class SettingsOverridesTest {

    private static Map<String, String> args(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void noFlagsKeepTheSettings() {
        HarnessSettings s = HarnessSettings.empty();
        assertEquals(s, SettingsOverrides.applyToSettings(Map.of(), s));
    }

    @Test
    void contextFlagsReplaceTheSettings() {
        HarnessSettings s = SettingsOverrides.applyToSettings(args("context-tokens", "50000",
                "context-strategy", "mnemonic", "small-model", ""), HarnessSettings.empty());
        assertEquals(50000, s.context().maxTokens());
        assertEquals("mnemonic", s.context().strategy());
        assertEquals(Boolean.TRUE, s.smallModel());
    }

    @Test
    void nonPositiveContextTokensAndFalseFlagsAreIgnored() {
        HarnessSettings s = SettingsOverrides.applyToSettings(args("context-tokens", "0", "small-model", "false"),
                HarnessSettings.empty());
        assertNull(s.context());
        assertNull(s.smallModel());
    }

    @Test
    void anUnknownContextStrategyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SettingsOverrides.applyToSettings(args("context-strategy", "magic"), HarnessSettings.empty()));
    }

    @Test
    void sandboxFlagsSelectDockerDaemon() {
        HarnessSettings s = SettingsOverrides.applyToSettings(args("sandbox", "docker", "docker-image", "img:1",
                "docker-mode", "daemon", "docker-remote-host", "tcp://h:2375"), HarnessSettings.empty());
        assertTrue(s.dockerSandbox());
        assertTrue(s.dockerDaemon());
        assertEquals("img:1", s.sandbox().docker().image());
        assertEquals("tcp://h:2375", s.sandbox().docker().remoteHost());
        assertNull(s.sandbox().docker().remote(), "only the host was given");

        HarnessSettings remote = SettingsOverrides.applyToSettings(args("docker-remote", ""), HarnessSettings.empty());
        assertEquals(Boolean.TRUE, remote.sandbox().docker().remote());
    }

    @Test
    void badSandboxValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SettingsOverrides.applyToSettings(args("sandbox", "vm"), HarnessSettings.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> SettingsOverrides.applyToSettings(args("docker-mode", "forever"), HarnessSettings.empty()));
    }

    @Test
    void pluginOptionsAreAppliedForThisRun() {
        HarnessSettings s = SettingsOverrides.applyToSettings(args("plugins.js.enabled", "true",
                "plugins.js.settings.timeout", "5"), HarnessSettings.empty());
        assertTrue(s.plugins().path("js").path("enabled").asBoolean());
        assertEquals(5, s.plugins().path("js").path("settings").path("timeout").asInt());
        assertThrows(IllegalArgumentException.class,
                () -> SettingsOverrides.applyToSettings(args("plugins.js.other", "x"), HarnessSettings.empty()));
    }

    @Test
    void configTakesTurnsTokensAndTheSearchKey() {
        HarnessConfig c = SettingsOverrides.applyToConfig(args("max-turns", "7", "max-tokens", "999",
                "search-api-key", "flag-key"), HarnessConfig.defaults(), "env-key");
        assertEquals(7, c.maxTurns());
        assertEquals(999, c.maxTokens());
        assertEquals("flag-key", c.web().searchApiKey(), "the flag wins over the environment");
    }

    @Test
    void configDefaultsWithoutFlags() {
        HarnessConfig defaults = HarnessConfig.defaults();
        HarnessConfig c = SettingsOverrides.applyToConfig(args("max-turns", "0"), defaults, "env-key");
        assertEquals(defaults.maxTurns(), c.maxTurns(), "a non-positive turn limit keeps the default");
        assertEquals(SettingsOverrides.DEFAULT_MAX_TOKENS, c.maxTokens());
        assertEquals("env-key", c.web().searchApiKey());

        assertEquals(defaults.web().searchApiKey(),
                SettingsOverrides.applyToConfig(Map.of(), defaults, null).web().searchApiKey());
    }

    @Test
    void nonNumericValuesFail() {
        assertThrows(NumberFormatException.class,
                () -> SettingsOverrides.applyToConfig(args("max-turns", "many"), HarnessConfig.defaults(), null));
        assertThrows(NumberFormatException.class,
                () -> SettingsOverrides.applyToSettings(args("context-tokens", "big"), HarnessSettings.empty()));
    }
}

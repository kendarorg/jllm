package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.llm.ProviderCatalog;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code --provider}, {@code --model}, {@code --base-url} and {@code --api-key} over the settings list. */
class ProviderChooserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static Map<String, String> args(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private HarnessSettings settings(String json) throws Exception {
        return HarnessSettings.load(Files.writeString(tmp.resolve("settings.json"), json));
    }

    private static final String LOCAL = """
            {"providers": [{"name": "local", "type": "openai", "baseUrl": "http://127.0.0.1:1/v1",
                            "apiKey": "file-key", "model": "file-model", "maxTokens": 777, "stream": false}],
             "defaultProvider": "local"}
            """;

    @Test
    void withoutFlagsTheDefaultProviderOfTheSettingsIsUsedAsIs() throws Exception {
        var c = ProviderChooser.choose(Map.of(), settings(LOCAL), MAPPER, 32000);
        assertEquals("local", c.name());
        assertNull(c.session(), "no session entry without overrides");
        assertNotNull(c.client());
    }

    @Test
    void withoutSettingsTheDefaultIsAnthropic() {
        var c = ProviderChooser.choose(Map.of(), HarnessSettings.empty(), MAPPER, 32000);
        assertEquals("anthropic", c.name());
        assertNull(c.session());
    }

    @Test
    void aBuiltInIsPickedByName() {
        var c = ProviderChooser.choose(args("provider", "ollama"), HarnessSettings.empty(), MAPPER, 32000);
        assertEquals("ollama", c.name());
    }

    @Test
    void anUnknownProviderWithoutAnEndpointIsRejectedListingTheKnownOnes() throws Exception {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ProviderChooser.choose(args("provider", "nope"), settings(LOCAL), MAPPER, 32000));
        assertTrue(e.getMessage().contains("Unknown provider 'nope'"), e.getMessage());
        assertTrue(e.getMessage().contains("local"), e.getMessage());
        assertTrue(e.getMessage().contains("--base-url and --model"), e.getMessage());
    }

    @Test
    void anUnknownProviderWithAnEndpointBecomesAnOpenAiSessionEntry() {
        var c = ProviderChooser.choose(args("provider", "mine", "base-url", "http://h/v1", "model", "m",
                "api-key", "k"), HarnessSettings.empty(), MAPPER, 1234);
        assertEquals(ProviderCatalog.CUSTOM, c.name());
        assertEquals("openai", c.session().type());
        assertEquals("http://h/v1", c.session().baseUrl());
        assertEquals("m", c.session().model());
        assertEquals("k", c.session().apiKey());
        assertEquals(1234, c.session().maxTokens());
    }

    @Test
    void overridesKeepTheRestOfTheConfiguredEntry() throws Exception {
        var c = ProviderChooser.choose(args("model", "flag-model"), settings(LOCAL), MAPPER, 32000);
        assertEquals(ProviderCatalog.CUSTOM, c.name());
        assertEquals("flag-model", c.session().model());
        assertEquals("http://127.0.0.1:1/v1", c.session().baseUrl());
        assertEquals("file-key", c.session().apiKey());
        assertEquals(777, c.session().maxTokens());
        assertFalse(c.session().streams(), "a provider configured not to stream keeps not streaming");
    }

    @Test
    void anApiKeyAloneOverridesABuiltIn() {
        var c = ProviderChooser.choose(args("provider", "openai", "api-key", "k2"), HarnessSettings.empty(), MAPPER,
                32000);
        assertEquals(ProviderCatalog.CUSTOM, c.name());
        assertEquals("openai", c.session().type());
        assertEquals("k2", c.session().apiKey());
        assertEquals(32000, c.session().maxTokens());
    }
}

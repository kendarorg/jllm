package org.kendar.llm.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliArgsTest {

    @Test
    void parsesSpacedAndEqualsForms() {
        Map<String, String> m = CliArgs.parse(new String[]{"--model", "m1", "--base-url=http://x?a=b", "--prompt", "hi"});
        assertEquals("m1", m.get("model"));
        assertEquals("http://x?a=b", m.get("base-url"));
        assertEquals("hi", m.get("prompt"));
    }

    @Test
    void bareFlagsGetEmptyValue() {
        Map<String, String> m = CliArgs.parse(new String[]{"--small-model", "--docker-remote", "--sandbox-clean",
                "--sandbox-clean-all", "-h"});
        assertEquals("", m.get("small-model"));
        assertEquals("", m.get("docker-remote"));
        assertEquals("", m.get("sandbox-clean"));
        assertEquals("", m.get("sandbox-clean-all"));
        assertTrue(m.containsKey("help"));
        assertTrue(CliArgs.parse(new String[]{"--help"}).containsKey("help"));
    }

    @Test
    void pluginOptionsKeepDottedKeys() {
        Map<String, String> m = CliArgs.parse(new String[]{"--plugins.js.enabled=true"});
        assertEquals("true", m.get("plugins.js.enabled"));
    }

    @Test
    void rejectsPositionalAndMissingValue() {
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[]{"stray"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[]{"--model"}));
    }

    @Test
    void flagAcceptsBareAndExplicitValues() {
        assertTrue(CliArgs.flag(Map.of("f", ""), "f"));
        assertTrue(CliArgs.flag(Map.of("f", "true"), "f"));
        assertFalse(CliArgs.flag(Map.of("f", "FALSE"), "f"));
        assertFalse(CliArgs.flag(Map.of(), "f"));
    }

    @Test
    void numDefaultsToZero() {
        assertEquals(1.5, CliArgs.num(Map.of("n", "1.5"), "n"));
        assertEquals(0, CliArgs.num(Map.of(), "n"));
        assertThrows(NumberFormatException.class, () -> CliArgs.num(Map.of("n", "x"), "n"));
    }

    @Test
    void usageMentionsMainFlags() {
        assertTrue(CliArgs.USAGE.contains("--provider"));
        assertTrue(CliArgs.USAGE.contains("--resume"));
    }
}

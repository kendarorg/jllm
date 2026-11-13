package org.kendar.llm.harness.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WildcardTest {

    @Test
    void starMatchesAnyRunIncludingNone() {
        assertTrue(Wildcard.matches("*", "ANYTHING"));
        assertTrue(Wildcard.matches("*", ""));
        assertTrue(Wildcard.matches("ANTHROPIC_*", "ANTHROPIC_API_KEY"));
        assertTrue(Wildcard.matches("*KEY", "ANTHROPIC_API_KEY"));
        assertTrue(Wildcard.matches("*key*", "ANTHROPIC_API_KEY"));
        assertTrue(Wildcard.matches("*key*test", "MY_KEY_FOR_TEST"));
        assertTrue(Wildcard.matches("a*b*c", "abc"));
        assertTrue(Wildcard.matches("a**c", "ac"));
    }

    @Test
    void theWholeNameMustMatchAndTheOrderOfPartsCounts() {
        assertFalse(Wildcard.matches("*key*test", "MY_KEY_FOR_TESTING"));
        assertFalse(Wildcard.matches("*key*test", "TEST_KEY"));
        assertFalse(Wildcard.matches("PATH", "PATHEXT"));
        assertFalse(Wildcard.matches("a*a", "a"));
        assertFalse(Wildcard.matches("ab*bc", "abc"));
        assertFalse(Wildcard.matches("*key*", "HOME"));
    }

    @Test
    void matchingIgnoresCaseAndTreatsOtherCharactersLiterally() {
        assertTrue(Wildcard.matches("path", "PATH"));
        assertTrue(Wildcard.matches("Program*(x86)", "PROGRAMFILES(X86)"));
        assertFalse(Wildcard.matches("a.c", "abc"));
        assertFalse(Wildcard.matches("a?c", "abc"));
    }

    @Test
    void anEmptyOrMissingListMatchesNothing() {
        assertFalse(Wildcard.matchesAny(List.of(), "X"));
        assertFalse(Wildcard.matchesAny(null, "X"));
        assertTrue(Wildcard.matchesAny(List.of("A", "X*"), "XY"));
    }

    @Test
    void aPathologicalPatternIsFast() {
        String text = "a".repeat(10_000) + "b";
        long t = System.nanoTime();
        assertFalse(Wildcard.matches("*a*a*a*a*a*a*a*a*a*c", text));
        assertTrue((System.nanoTime() - t) / 1_000_000 < 1000);
    }

    @Test
    void whitelistFiltersAnEnvironmentMap() {
        Map<String, String> env = new LinkedHashMap<>(Map.of("PATH", "p", "HOME", "h", "MY_API_KEY", "k", "TOKEN_TEST", "t"));

        EnvWhitelist.apply(env, List.of("PATH", "*key"));

        assertEquals(Map.of("PATH", "p", "MY_API_KEY", "k"), env);

        Map<String, String> all = new LinkedHashMap<>(Map.of("A", "1", "B", "2"));
        EnvWhitelist.apply(all, EnvWhitelist.ALL);
        assertEquals(2, all.size());

        Map<String, String> none = new LinkedHashMap<>(Map.of("A", "1"));
        EnvWhitelist.apply(none, List.of());
        assertTrue(none.isEmpty());
        assertEquals(EnvWhitelist.ALL, EnvWhitelist.orAll(null));
    }
}

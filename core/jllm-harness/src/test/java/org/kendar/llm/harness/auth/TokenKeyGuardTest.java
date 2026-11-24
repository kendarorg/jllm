package org.kendar.llm.harness.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TokenKeyGuardTest {

    private static boolean check(Map<String, String> env, Map<String, String> props, List<String> warnings) {
        return TokenKeyGuard.check(env::get, props::get, warnings::add);
    }

    @Test
    void keyPresentIsQuietWhateverTheExposure() {
        List<String> w = new ArrayList<>();
        assertTrue(check(Map.of("JLLM_TOKEN_KEY", TokenCipher.newKey()),
                Map.of("jllm.web.use-real-password", "true", "server.address", "0.0.0.0"), w));
        assertTrue(w.isEmpty());
    }

    @Test
    void loopbackWithoutKeyWarnsNamingTheVariableAndHowToGenerate() {
        List<String> w = new ArrayList<>();
        assertFalse(check(Map.of(), Map.of("server.address", "127.0.0.1"), w));
        assertEquals(1, w.size());
        assertTrue(w.get(0).contains("JLLM_TOKEN_KEY") && w.get(0).contains("32 random bytes")
                && w.get(0).contains("base64"), w.get(0));
        assertFalse(check(Map.of("JLLM_TOKEN_KEY", " "), Map.of("server.address", "localhost"), w));
        assertFalse(check(Map.of(), Map.of("server.address", "::1"), w));
        assertEquals(3, w.size());
    }

    @Test
    void realPasswordsOrANonLoopbackAddressRefuseWithoutKey() {
        for (Map<String, String> props : List.of(
                Map.of("jllm.web.use-real-password", "true", "server.address", "127.0.0.1"),
                Map.<String, String>of(),
                Map.of("server.address", ""),
                Map.of("server.address", "0.0.0.0"),
                Map.of("server.address", "::"),
                Map.of("server.address", "192.168.1.5"),
                Map.of("server.address", "example.com"))) {
            List<String> w = new ArrayList<>();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(Map.of(), props, w),
                    props.toString());
            assertTrue(e.getMessage().contains("JLLM_TOKEN_KEY") && e.getMessage().contains("allow-unencrypted-tokens"),
                    e.getMessage());
            assertTrue(w.isEmpty());
        }
    }

    @Test
    void overrideStartsWithALoudWarning() {
        List<String> w = new ArrayList<>();
        assertFalse(check(Map.of(), Map.of("jllm.web.use-real-password", "true",
                "jllm.web.allow-unencrypted-tokens", "true"), w));
        assertEquals(1, w.size());
        assertTrue(w.get(0).contains("JLLM_TOKEN_KEY") && w.get(0).contains("CLEAR TEXT"), w.get(0));
        assertThrows(IllegalStateException.class, () -> check(Map.of(), Map.of("jllm.web.allow-unencrypted-tokens",
                "false"), new ArrayList<>()));
    }
}

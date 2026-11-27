package org.kendar.llm.harness.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvWhitelistTest {

    @Test
    void defaultIsPathHomeLangTerm() {
        assertEquals(List.of("PATH", "HOME", "LANG", "TERM"), EnvWhitelist.DEFAULT);
        assertTrue(EnvWhitelist.defaults().containsAll(EnvWhitelist.DEFAULT));
        assertEquals(EnvWhitelist.defaults(), EnvWhitelist.orDefault(null));
        assertEquals(List.of(), EnvWhitelist.orDefault(List.of()));
    }

    @Test
    void secretsAreRecognizedInAnyCase() {
        for (String n : List.of("JLLM_HOME", "jllm_token", "OPENAI_API_KEY", "my_apikey", "AWS_SECRET_ACCESS_KEY",
                "DB_PASSWORD", "db_Password", "SSH_KEY", "Monkey")) {
            assertTrue(EnvWhitelist.isSecret(n), n);
        }
        for (String n : List.of("PATH", "HOME", "LANG", "TERM", "JAVA_HOME", "USER")) {
            assertFalse(EnvWhitelist.isSecret(n), n);
        }
    }

    @Test
    void secretsAreStrippedEvenWhenWhitelisted() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String n : List.of("PATH", "HOME", "JLLM_ADMIN_PASSWORD", "OPENAI_API_KEY", "X_SECRET", "db_password", "OTHER")) {
            env.put(n, "v");
        }

        EnvWhitelist.apply(env, List.of("*"));

        assertEquals(List.of("PATH", "HOME", "OTHER"), List.copyOf(env.keySet()));

        Map<String, String> named = new LinkedHashMap<>(Map.of("PATH", "p", "JLLM_X", "1", "MY_API_KEY", "k"));
        EnvWhitelist.apply(named, List.of("PATH", "JLLM_X", "MY_API_KEY"));
        assertEquals(Map.of("PATH", "p"), named);
    }

    @Test
    void nothingPassesWithAnEmptyList() {
        Map<String, String> env = new LinkedHashMap<>(Map.of("PATH", "p"));
        EnvWhitelist.apply(env, List.of());
        assertTrue(env.isEmpty());
    }
}

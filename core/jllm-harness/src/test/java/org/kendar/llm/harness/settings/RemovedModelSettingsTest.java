package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The inline fastModel and agent model are gone: a provider marked fast and the agent's provider replace them. */
class RemovedModelSettingsTest {

    private static String failure(String json) {
        List<String> warnings = new ArrayList<>();
        ObjectMapper m = HarnessSettings.mapper(warnings::add);
        Throwable t = assertThrows(Exception.class, () -> m.readValue(json, HarnessSettings.class),
                () -> "warnings: " + warnings);
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append('\n');
        return sb.toString();
    }

    @Test
    void anOldFastModelIsAStartupErrorNamingTheReplacement() {
        String m = failure("{\"fastModel\":{\"name\":\"openai\"}}");
        assertTrue(m.contains("fastModel") && m.contains("\"fast\": true"), m);
    }

    @Test
    void anAgentInlineModelIsAStartupErrorNamingTheReplacement() {
        String m = failure("{\"agents\":[{\"name\":\"a\",\"model\":{\"name\":\"openai\"}}]}");
        assertTrue(m.contains("'provider'"), m);
    }

    @Test
    void aProviderMarkedFastAndAnAgentProviderAreRead() throws Exception {
        List<String> warnings = new ArrayList<>();
        HarnessSettings s = HarnessSettings.mapper(warnings::add).readValue(
                "{\"providers\":[{\"name\":\"cheap\",\"type\":\"openai\",\"fast\":true}],"
                        + "\"agents\":[{\"name\":\"a\",\"provider\":\"cheap\"}]}", HarnessSettings.class);
        assertTrue(s.providers().get(0).isFast());
        assertEquals("cheap", s.agents().get(0).provider());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}

package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GeminiProviderTest {

    @Test
    void geminiUsesTheOpenAiCompatibleEndpoint() {
        assertInstanceOf(OpenAiCompatModelClient.class,
                ModelClientFactory.create("gemini", "k", null, null, 1000, new ObjectMapper()));
        LlmSettings s = new ProviderSettings("gemini", null, null, "k", null, null, null, null, null, null, null, null, null, null, null).toLlmSettings();
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", s.baseUrl());
        assertEquals("gemini-2.5-pro", s.model());
        assertEquals(true, s.requireApiKey());
    }
}

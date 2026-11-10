package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GrokProviderTest {

    @Test
    void grokUsesTheOpenAiCompatibleEndpoint() {
        assertInstanceOf(OpenAiCompatModelClient.class,
                ModelClientFactory.create("grok", "k", null, null, 1000, new ObjectMapper()));
        LlmSettings s = new ProviderSettings("grok", null, null, "k", null, null, null, null, null, null, null, null, null, null, null).toLlmSettings();
        assertEquals("https://api.x.ai/v1", s.baseUrl());
        assertEquals("grok-4", s.model());
        assertEquals(true, s.requireApiKey());
    }
}

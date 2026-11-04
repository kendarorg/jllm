package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleToolTest {

    @Test
    void schemaMarksOptionalAndTypes() {
        JsonNode s = SimpleTool.schema(
                "path", "a path",
                "limit:integer?", "max lines");

        assertEquals("object", s.get("type").asText());
        assertEquals("string", s.get("properties").get("path").get("type").asText());
        assertEquals("integer", s.get("properties").get("limit").get("type").asText());
        assertEquals(1, s.get("required").size());
        assertEquals("path", s.get("required").get(0).asText());
    }
}

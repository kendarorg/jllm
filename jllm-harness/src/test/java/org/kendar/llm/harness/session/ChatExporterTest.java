package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatExporterTest {

    private static final ObjectMapper M = new ObjectMapper();

    private List<JsonNode> log() throws Exception {
        return List.of(
                M.readTree("{\"role\":\"user\",\"content\":\"hello <b>\"}"),
                M.readTree("{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"reading\"},"
                        + "{\"type\":\"tool_use\",\"name\":\"read_file\",\"input\":{\"path\":\"a\"}}]}"),
                M.readTree("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"1\","
                        + "\"content\":\"file text\",\"is_error\":true}]}"));
    }

    @Test
    void markdownHasRolesToolCallsAndResults() throws Exception {
        String md = ChatExporter.markdown("T", log());
        assertTrue(md.startsWith("# T"));
        assertTrue(md.contains("## user"));
        assertTrue(md.contains("## assistant\n\nreading"));
        assertTrue(md.contains("### tool call: read_file"));
        assertTrue(md.contains("### tool result (error)"));
        assertTrue(md.contains("file text"));
    }

    @Test
    void htmlEscapesContent() throws Exception {
        String html = ChatExporter.html("T", log());
        assertTrue(html.contains("hello &lt;b&gt;"));
        assertFalse(html.contains("hello <b>"));
    }
}

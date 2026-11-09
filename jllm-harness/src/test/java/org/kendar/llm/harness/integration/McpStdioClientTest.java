package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kendar.llm.harness.settings.McpServerSettings;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpStdioClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Answers initialize (id 1), skips the notification, then tools/list (id 2) and tools/call (id 3). */
    private static final String FAKE_SERVER = """
            read initialize
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{}}}'
            read notification
            read list
            echo '{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]}}'
            read call
            echo '{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hi"}]}}'
            """;

    @Test
    void listsToolsAndCallsThemOverStdin() throws Exception {
        try (McpStdioClient client = new McpStdioClient(MAPPER, "fake", "sh", List.of("-c", FAKE_SERVER), Map.of())) {
            List<JsonNode> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).path("name").asText());

            JsonNode result = client.callTool("echo", MAPPER.createObjectNode());
            assertEquals("hi", result.path("content").get(0).path("text").asText());
        }
    }

    @Test
    void failsWhenTheServerExitsBeforeAnswering() {
        try (McpStdioClient client = new McpStdioClient(MAPPER, "gone", "sh", List.of("-c", "exit 0"), Map.of())) {
            assertThrows(IOException.class, client::listTools);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidServers")
    void serverNeedsExactlyOneTransport(String url, String command) {
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerSettings("srv", url, null, null, null, null, command, null, null));
    }

    static Stream<Arguments> invalidServers() {
        return Stream.of(
                Arguments.of("http://localhost/mcp", "adexpert-python"),
                Arguments.of(null, null),
                Arguments.of("", " "));
    }
}

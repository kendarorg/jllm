package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kendar.llm.harness.settings.McpServerSettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpStdioClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Portable fake server: the current JVM running {@link FakeMcpServer} (no dependency on sh). */
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private static final List<String> FAKE_ARGS = List.of("-cp", System.getProperty("java.class.path"),
            FakeMcpServer.class.getName());
    private static final List<String> EXIT_ARGS = List.of("-cp", System.getProperty("java.class.path"),
            FakeMcpServer.class.getName(), "exit");

    /** Answers initialize (id 1), skips the notification, then tools/list (id 2) and tools/call (id 3). */
    public static final class FakeMcpServer {
        public static void main(String[] args) throws Exception {
            if (args.length > 0) return;
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            in.readLine();
            reply("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}}");
            in.readLine();
            in.readLine();
            reply("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}}");
            in.readLine();
            reply("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}");
        }

        private static void reply(String json) {
            System.out.println(json);
            System.out.flush();
        }
    }

    @Test
    void listsToolsAndCallsThemOverStdin() throws Exception {
        try (McpStdioClient client = new McpStdioClient(MAPPER, "fake", JAVA, FAKE_ARGS, Map.of())) {
            List<JsonNode> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).path("name").asText());

            JsonNode result = client.callTool("echo", MAPPER.createObjectNode());
            assertEquals("hi", result.path("content").get(0).path("text").asText());
        }
    }

    @Test
    void failsWhenTheServerExitsBeforeAnswering() {
        try (McpStdioClient client = new McpStdioClient(MAPPER, "gone", JAVA, EXIT_ARGS, Map.of())) {
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

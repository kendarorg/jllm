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

import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * Answers initialize and tools/list in a loop. Modes: {@code crash} exits on tools/call; {@code once <marker>}
     * does the same but, when the marker file exists, exits at once (every restart fails); {@code hang} never answers.
     */
    public static final class LoopServer {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            if (mode.equals("once")) {
                java.nio.file.Path marker = java.nio.file.Path.of(args[1]);
                if (java.nio.file.Files.exists(marker)) System.exit(3);
                java.nio.file.Files.createFile(marker);
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null; ) {
                if (mode.equals("hang")) continue;
                java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(line);
                if (!id.find()) continue;
                if (line.contains("\"tools/call\"")) System.exit(1);
                String result = line.contains("\"tools/list\"") ? "{\"tools\":[{\"name\":\"echo\"}]}" : "{}";
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}");
                System.out.flush();
            }
        }
    }

    private static List<String> loop(String... args) {
        List<String> out = new java.util.ArrayList<>(List.of("-cp", System.getProperty("java.class.path"),
                LoopServer.class.getName()));
        out.addAll(List.of(args));
        return out;
    }

    @Test
    void crashedServerIsRestartedOnTheNextCall() throws Exception {
        try (McpStdioClient client = new McpStdioClient(MAPPER, "crash", JAVA, loop("crash"), Map.of())) {
            assertEquals(1, client.listTools().size());
            IOException crash = assertThrows(IOException.class, () -> client.callTool("echo", null));
            assertFalse(crash instanceof McpUnavailableException);
            assertEquals(1, client.listTools().size());
            assertNull(client.unavailableReason());
        }
    }

    @Test
    void serverThatCannotBeRestartedBecomesUnavailable(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        List<String> reasons = new java.util.concurrent.CopyOnWriteArrayList<>();
        String marker = tmp.resolve("started").toString();
        try (McpStdioClient client = new McpStdioClient(MAPPER, "once", JAVA, loop("once", marker), Map.of())
                .onUnavailable(reasons::add)) {
            assertEquals(1, client.listTools().size());
            assertThrows(IOException.class, () -> client.callTool("echo", null));

            McpUnavailableException e = assertThrows(McpUnavailableException.class, client::listTools);
            assertTrue(e.getMessage().contains(McpStdioClient.MAX_RESTART_TRIES + " tries"), e.getMessage());
            assertThrows(McpUnavailableException.class, client::listTools);
            assertEquals(1, reasons.size());
        }
    }

    @Test
    void closeDoesNotWaitForAPendingInitialize() throws Exception {
        McpStdioClient client = new McpStdioClient(MAPPER, "hang", JAVA, loop("hang"), Map.of());
        java.util.concurrent.CompletableFuture<Throwable> init = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                client.initialize();
                return null;
            } catch (Exception e) {
                return e;
            }
        });
        Thread.sleep(1500); // the process is started and the handshake is waiting
        long start = System.nanoTime();
        client.close();
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "close() waited for initialize");
        Throwable failure = init.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(failure instanceof IOException, String.valueOf(failure));
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
                () -> new McpServerSettings("srv", url, null, null, null, null, command, null, null, null, null, null, null));
    }

    static Stream<Arguments> invalidServers() {
        return Stream.of(
                Arguments.of("http://localhost/mcp", "adexpert-python"),
                Arguments.of(null, null),
                Arguments.of("", " "));
    }
}

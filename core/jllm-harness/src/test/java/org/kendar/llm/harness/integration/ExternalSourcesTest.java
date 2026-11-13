package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.agent.AgentRun;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSourcesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @TempDir
    Path tmp;

    /** Writes its pid to {@code args[0]}, then offers the single tool {@code args[1]} until stdin closes. */
    public static final class PidServer {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null; ) {
                java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(line);
                if (!id.find()) continue;
                String result = line.contains("\"tools/list\"")
                        ? "{\"tools\":[{\"name\":\"" + args[1] + "\"}]}"
                        : line.contains("\"tools/call\"") ? "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}" : "{}";
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}");
                System.out.flush();
            }
        }
    }

    private McpServerSettings pidServer(String name, String tool, Boolean enabled) {
        String pidFile = tmp.resolve(name + ".pid").toString();
        return new McpServerSettings(name, null, null, null, enabled, null, JAVA,
                List.of("-cp", System.getProperty("java.class.path"), PidServer.class.getName(), pidFile, tool), null);
    }

    private long pid(String name) throws IOException {
        return Long.parseLong(Files.readString(tmp.resolve(name + ".pid")).trim());
    }

    private static void assertStops(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true)) return;
            Thread.sleep(100);
        }
        fail("MCP server process " + pid + " is still running");
    }

    private static HarnessSettings settings(McpServerSettings... servers) {
        return new HarnessSettings(List.of(servers), List.of(), null, null, null);
    }

    private static HarnessToolRegistry registry(String... coreTools) {
        List<HarnessTool> core = new ArrayList<>();
        for (String n : coreTools) {
            core.add(new SimpleTool(n, n, SimpleTool.schema(), "G", ToolKind.OTHER, (c, i) -> ""));
        }
        return new HarnessToolRegistry(core);
    }

    private static List<String> names(HarnessToolRegistry r) {
        return r.every().stream().map(HarnessTool::name).toList();
    }

    @Test
    void collisionWithAHarnessToolFailsStartupAndStopsEveryOpenedServer() throws Exception {
        HarnessToolRegistry registry = registry("mcp__clash__echo");
        HarnessSettings settings = settings(pidServer("first", "echo", null), pidServer("clash", "echo", null));

        ToolNames.CollisionException e = assertThrows(ToolNames.CollisionException.class,
                () -> new ExternalSources(registry, settings, HttpClient.newHttpClient(), MAPPER));

        assertTrue(e.getMessage().contains("harness tool 'mcp__clash__echo'"), e.getMessage());
        assertTrue(e.getMessage().contains("MCP server 'clash' tool 'echo'"), e.getMessage());
        assertStops(pid("first"));
        assertStops(pid("clash"));
    }

    @Test
    void collisionBetweenSourcesFailsStartupNamingBothTools() throws Exception {
        HarnessSettings settings = settings(pidServer("a__b", "c", null), pidServer("a", "b__c", null));

        ToolNames.CollisionException e = assertThrows(ToolNames.CollisionException.class,
                () -> new ExternalSources(registry(), settings, HttpClient.newHttpClient(), MAPPER));

        assertEquals("mcp__a__b__c", e.toolName());
        assertEquals("MCP server 'a__b' tool 'c'", e.first());
        assertEquals("MCP server 'a' tool 'b__c'", e.second());
        assertStops(pid("a__b"));
        assertStops(pid("a"));
    }

    @Test
    void collisionAtRuntimeRefusesTheEnableOnly() throws Exception {
        HarnessToolRegistry registry = registry();
        try (ExternalSources sources = new ExternalSources(registry,
                settings(pidServer("a__b", "c", null), pidServer("a", "b__c", false)),
                HttpClient.newHttpClient(), MAPPER)) {
            String error = sources.enable(ExternalSources.Kind.MCP, "a");

            assertNotNull(error);
            assertTrue(error.contains("MCP server 'a__b' tool 'c'") && error.contains("MCP server 'a' tool 'b__c'"),
                    error);
            assertFalse(sources.list(ExternalSources.Kind.MCP).get(1).enabled());
            assertEquals(List.of("mcp__a__b__c"), names(registry));
            assertStops(pid("a"));

            sources.disable(ExternalSources.Kind.MCP, "a__b"); // frees the name
            assertNull(sources.enable(ExternalSources.Kind.MCP, "a"));
            assertEquals(List.of("mcp__a__b__c"), names(registry));
        }
    }

    @Test
    void unreachableServerAtStartupOnlyDisablesItsSource() {
        McpServerSettings gone = new McpServerSettings("gone", null, null, null, null, null, JAVA,
                List.of("-cp", System.getProperty("java.class.path"), "no.such.Main"), null);
        try (ExternalSources sources = new ExternalSources(registry(), settings(gone), HttpClient.newHttpClient(),
                MAPPER)) {
            ExternalSources.Status s = sources.list(ExternalSources.Kind.MCP).get(0);
            assertFalse(s.enabled());
            assertNotNull(s.error());
        }
    }

    @Test
    void closeStopsStdioServersAndIsIdempotent() throws Exception {
        ExternalSources sources = new ExternalSources(registry(), settings(pidServer("srv", "echo", null)),
                HttpClient.newHttpClient(), MAPPER);
        long pid = pid("srv");
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));

        sources.close();
        assertStops(pid);
        sources.close();
        assertNotNull(sources.enable(ExternalSources.Kind.MCP, "srv"));
    }

    @Test
    void serverLostForGoodIsDisabledAndTheRunIsWarned() throws Exception {
        String marker = tmp.resolve("started").toString();
        McpServerSettings once = new McpServerSettings("once", null, null, null, null, null, JAVA,
                List.of("-cp", System.getProperty("java.class.path"),
                        McpStdioClientTest.LoopServer.class.getName(), "once", marker), null);
        HarnessToolRegistry registry = registry();
        try (ExternalSources sources = new ExternalSources(registry, settings(once), HttpClient.newHttpClient(),
                MAPPER)) {
            HarnessTool echo = registry.get("mcp__once__echo");
            assertNotNull(echo);
            List<String> warnings = new CopyOnWriteArrayList<>();
            AgentRun run = new AgentRun((type, data) -> {
                if (type.equals("warning")) warnings.add(data.path("message").asText());
            }, () -> false);
            ToolContext ctx = new ToolContext("chat", tmp, tmp, false, "t1",
                    org.kendar.llm.harness.permission.PermissionMode.DEFAULT, run);
            JsonNode input = MAPPER.createObjectNode();

            assertThrows(IOException.class, () -> echo.execute(ctx, input)); // the server exits on tools/call
            assertThrows(McpUnavailableException.class, () -> echo.execute(ctx, input)); // restarts fail

            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("MCP server 'once' disabled"), warnings.get(0));
            ExternalSources.Status s = sources.list(ExternalSources.Kind.MCP).get(0);
            assertFalse(s.enabled());
            assertTrue(s.error().contains("could not be restarted"), s.error());
            assertNull(registry.get("mcp__once__echo"));
        }
    }
}

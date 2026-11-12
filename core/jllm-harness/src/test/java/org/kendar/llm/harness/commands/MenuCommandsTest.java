package org.kendar.llm.harness.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.ToolKind;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class MenuCommandsTest {

    private static final String SPEC = "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n  /ping:\n    get:\n      operationId: ping\n";

    @TempDir
    Path base;

    private HttpServer server;
    private String url;
    private volatile boolean mcpUp = true;
    private AgentHarness harness;
    private String chat;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec.yaml", ex -> reply(ex, 200, SPEC));
        server.createContext("/api", ex -> reply(ex, 404, "nope"));
        server.createContext("/mcp", ex -> {
            if (!mcpUp) {
                reply(ex, 503, "down");
                return;
            }
            JsonNode req = MAPPER.readTree(ex.getRequestBody());
            String method = req.path("method").asText();
            String id = req.path("id").toString();
            if (method.startsWith("notifications/")) reply(ex, 202, "");
            else if (method.equals("tools/list")) {
                reply(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                        + "{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]}}");
            } else reply(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}");
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();

        HarnessSettings settings = new HarnessSettings(
                List.of(new McpServerSettings("srv", url + "/mcp", null, null, null, null)),
                List.of(new RestApiSettings("pets", url + "/api", url + "/spec.yaml", null, null, null,
                        List.of("/ping"), false)), null, null, null);
        harness = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base))
                .withoutStandardTools()
                .tools(List.of(tool("alpha", "Misc", ToolKind.READ_ONLY, (c, i) -> ""),
                        tool("beta", "Misc", ToolKind.OTHER, (c, i) -> "")))
                .settings(settings).build();
        chat = harness.sessions().create(null).chatId();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private List<String> enabledTools() {
        return harness.tools().all().stream().map(HarnessTool::name).toList();
    }

    @Test
    void registryTogglesAndSourcesKeepOrderAndRollBackOnClash() {
        HarnessToolRegistry r = new HarnessToolRegistry(List.of(tool("b", "G", ToolKind.OTHER, (c, i) -> "")));
        r.setSource("s", List.of(tool("a", "G", ToolKind.OTHER, (c, i) -> "")));
        assertEquals(List.of("a", "b"), r.all().stream().map(HarnessTool::name).toList());

        r.disable(List.of("a"));
        assertEquals(List.of("b"), r.all().stream().map(HarnessTool::name).toList());
        assertEquals(2, r.every().size());

        assertThrows(IllegalArgumentException.class,
                () -> r.setSource("t", List.of(tool("b", "G", ToolKind.OTHER, (c, i) -> ""))));
        assertEquals(2, r.every().size());

        r.removeSource("s");
        assertEquals(List.of("b"), r.every().stream().map(HarnessTool::name).toList());
    }

    @Test
    void startsWithEnabledSourcesOnlyAndEnablesOthersOnDemand() {
        assertTrue(enabledTools().contains("mcp__srv__echo"));
        assertFalse(enabledTools().stream().anyMatch(n -> n.startsWith("pets__")));

        assertEquals(null, harness.sources().enable(org.kendar.llm.harness.integration.ExternalSources.Kind.API, "pets"));
        assertTrue(enabledTools().contains("pets__ping"));
    }

    @Test
    void toolsMenuTogglesATool() {
        SlashCommands c = harness.commands();
        String list = c.execute(chat, "/tools").output();
        assertTrue(c.menuActive(chat));
        assertTrue(list.contains("alpha"), list);

        int alpha = numberOf(list, "alpha");
        String actions = c.menuInput(chat, String.valueOf(alpha)).output();
        assertTrue(actions.contains("1. Disable"), actions);
        assertFalse(actions.contains("Check"));

        String after = c.menuInput(chat, "1").output();
        assertTrue(after.startsWith("Disable: alpha"), after);
        assertFalse(enabledTools().contains("alpha"));
        assertTrue(after.contains("alpha") && after.contains("[off]"), after);
    }

    @Test
    void groupEntryTogglesWholeGroup() {
        SlashCommands c = harness.commands();
        String list = c.execute(chat, "/tools").output();
        c.menuInput(chat, String.valueOf(numberOf(list, "Misc")));
        c.menuInput(chat, "1");
        assertFalse(enabledTools().contains("alpha"));
        assertFalse(enabledTools().contains("beta"));
    }

    @Test
    void escGoesBackThenClosesAndBadInputReprompts() {
        SlashCommands c = harness.commands();
        String list = c.execute(chat, "/mcp").output();
        c.menuInput(chat, String.valueOf(numberOf(list, "srv")));

        String back = c.menuInput(chat, "\u001b").output();
        assertTrue(back.contains("MCP servers"), back);
        assertTrue(c.menuInput(chat, "x").output().contains("Type a number"));
        assertTrue(c.menuInput(chat, "9").output().contains("No item 9"));

        assertEquals("Menu closed", c.menuInput(chat, "\u001b").output());
        assertFalse(c.menuActive(chat));
    }

    @Test
    void mcpMenuChecksDisablesAndReportsUnreachable() {
        SlashCommands c = harness.commands();
        String list = c.execute(chat, "/mcp").output();
        int srv = numberOf(list, "srv");

        c.menuInput(chat, String.valueOf(srv));
        String ok = c.menuInput(chat, "2").output();
        assertTrue(ok.startsWith("OK srv"), ok);

        mcpUp = false;
        c.menuInput(chat, String.valueOf(srv));
        String bad = c.menuInput(chat, "2").output();
        assertTrue(bad.startsWith("FAILED srv"), bad);

        c.menuInput(chat, String.valueOf(srv));
        assertTrue(c.menuInput(chat, "1").output().startsWith("Disabled: srv"));
        assertFalse(enabledTools().contains("mcp__srv__echo"));

        c.menuInput(chat, String.valueOf(srv));
        String failed = c.menuInput(chat, "1").output();
        assertTrue(failed.startsWith("Cannot enable srv"), failed);
    }

    @Test
    void apisMenuChecksSpecAndBaseUrl() {
        SlashCommands c = harness.commands();
        String list = c.execute(chat, "/apis").output();
        c.menuInput(chat, String.valueOf(numberOf(list, "pets")));
        String result = c.menuInput(chat, "2").output();
        assertTrue(result.startsWith("OK pets"), result);
        assertTrue(result.contains("HTTP 404"), result);
    }

    @Test
    void scriptedFormsWork() {
        SlashCommands c = harness.commands();
        assertTrue(c.execute(chat, "/tools disable alpha").output().contains("Disabled: alpha"));
        assertFalse(enabledTools().contains("alpha"));
        assertTrue(c.execute(chat, "/mcp check srv").output().startsWith("OK srv"));
        assertTrue(c.execute(chat, "/mcp check").output().contains("srv"));
        assertTrue(c.execute(chat, "/mcp disable nope").output().contains("Unknown mcp 'nope'"));
        assertFalse(c.menuActive(chat));
    }

    /** Number of the first line that mentions {@code label}. */
    private static int numberOf(String menu, String label) {
        for (String line : menu.split("\n")) {
            if (line.contains(label)) return Integer.parseInt(line.strip().split("\\.")[0]);
        }
        throw new AssertionError("no line with " + label + " in:\n" + menu);
    }

    @Test
    void whereShowsWorkingAndScratchDirectories() {
        String out = harness.commands().execute(chat, "/where").output();

        assertTrue(out.contains("working directory: " + harness.workDir(chat)), out);
        assertTrue(out.contains("scratch directory: " + harness.scratchDir(chat)), out);
    }
}

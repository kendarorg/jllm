package org.kendar.llm.harness.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.permission.ApprovalService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class WebHostApprovalTest {

    @TempDir
    Path tmp;

    HttpServer server;
    HarnessConfig config;
    String ip;
    String name;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/plain", "text/plain", "just plain text".getBytes(StandardCharsets.UTF_8));
        serve("/bin", "application/octet-stream", new byte[]{0, (byte) 0xFF, (byte) 0xFE, 10, 13, 0, 1});
        serve("/big", "application/octet-stream", new byte[500]);
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", name + "/plain");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        ip = "http://127.0.0.1:" + port;
        name = "http://localhost:" + port;
        config = config(tmp);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void serve(String path, String type, byte[] body) {
        server.createContext(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private HarnessTool tool(WebAgentTools web, String n) {
        return web.tools().stream().filter(t -> t.name().equals(n)).findFirst().orElseThrow();
    }

    // ---- matching -------------------------------------------------------------------------------------------

    @Test
    void grantCoversSubdomainsOnLabelBoundaryOnly() {
        assertTrue(HostMatcher.covers("example.com", "example.com"));
        assertTrue(HostMatcher.covers("example.com", "www.example.com"));
        assertTrue(HostMatcher.covers("example.com", "a.b.example.com"));
        assertFalse(HostMatcher.covers("example.com", "badexample.com"));
        assertFalse(HostMatcher.covers("www.example.com", "example.com"));
    }

    @Test
    void ipLiteralsMatchOnlyThemselvesAndNamesAreNotResolved() {
        assertTrue(HostMatcher.covers("127.0.0.1", "127.0.0.1"));
        assertFalse(HostMatcher.covers("127.0.0.1", "localhost"));
        assertFalse(HostMatcher.covers("localhost", "127.0.0.1"));
        assertFalse(HostMatcher.covers("0.0.1", "127.0.0.1"));
        assertTrue(HostMatcher.covers("::1", "[::1]"));
    }

    @Test
    void caseTrailingDotAndIdnAreNormalised() {
        assertTrue(HostMatcher.covers("Example.COM", "WWW.example.com."));
        assertTrue(HostMatcher.covers("bücher.example", "xn--bcher-kva.example"));
    }

    @Test
    void grantsArePerChat() {
        ApprovalService a = new ApprovalService(5);
        a.grantHost("c1", "example.com");

        assertTrue(a.hostGranted("c1", "www.example.com"));
        assertFalse(a.hostGranted("c2", "www.example.com"));
        assertFalse(a.hostGranted("c1", "badexample.com"));
    }

    // ---- the tools ------------------------------------------------------------------------------------------

    @Test
    void fetchAsksForTheHostAndADenialFailsTheCall() throws Exception {
        WebAgentTools web = new WebAgentTools(config, MAPPER);
        List<String> asked = new ArrayList<>();
        ToolContext ctx = new ToolContext("c1", tmp).withHosts((h, u) -> {
            asked.add(h);
            return false;
        });

        Exception e = assertThrows(Exception.class,
                () -> tool(web, "web_fetch").execute(ctx, json("{'url':'" + ip + "/plain'}")));

        assertTrue(e.getMessage().contains("127.0.0.1"));
        assertEquals(List.of("127.0.0.1"), asked);
    }

    @Test
    void redirectToAnotherHostAsksForThatHost() throws Exception {
        WebAgentTools web = new WebAgentTools(config, MAPPER);
        List<String> asked = new ArrayList<>();
        ToolContext ctx = new ToolContext("c1", tmp).withHosts((h, u) -> {
            asked.add(h);
            return h.equals("127.0.0.1");
        });

        Exception e = assertThrows(Exception.class,
                () -> tool(web, "web_fetch").execute(ctx, json("{'url':'" + ip + "/redirect'}")));

        assertEquals(List.of("127.0.0.1", "localhost"), asked);
        assertTrue(e.getMessage().contains("localhost") && e.getMessage().contains("redirect"), e.getMessage());

        ToolContext allowAll = new ToolContext("c1", tmp).withHosts((h, u) -> true);
        assertEquals("HTTP 200\njust plain text",
                tool(web, "web_fetch").execute(allowAll, json("{'url':'" + ip + "/redirect'}")));
    }

    @Test
    void searchToolsNeverAsk() throws Exception {
        WebAgentTools web = new WebAgentTools(config.withSearchApiKey(""), MAPPER, ip + "/plain");
        List<String> asked = new ArrayList<>();
        ToolContext ctx = new ToolContext("c1", tmp).withHosts((h, u) -> {
            asked.add(h);
            return false;
        });

        tool(web, "web_search").execute(ctx, json("{'query':'x'}"));

        assertTrue(asked.isEmpty());
    }

    @Test
    void downloadWritesTheBytesUnchanged() throws Exception {
        WebAgentTools web = new WebAgentTools(config, MAPPER);
        ToolContext ctx = new ToolContext("c1", tmp);

        String out = tool(web, "web_download").execute(ctx, json("{'url':'" + ip + "/bin','path':'d/x.bin'}"));

        assertArrayEquals(new byte[]{0, (byte) 0xFF, (byte) 0xFE, 10, 13, 0, 1}, Files.readAllBytes(tmp.resolve("d/x.bin")));
        assertTrue(out.contains("7 bytes") && out.contains("application/octet-stream") && out.contains("d/x.bin"), out);
    }

    @Test
    void downloadAboveTheCapFailsAndLeavesNoFile() throws Exception {
        WebAgentTools web = new WebAgentTools(config.withDownloadMaxBytes(100), MAPPER);
        ToolContext ctx = new ToolContext("c1", tmp);

        Exception e = assertThrows(Exception.class,
                () -> tool(web, "web_download").execute(ctx, json("{'url':'" + ip + "/big','path':'big.bin'}")));

        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("big.bin")));
        try (var s = Files.list(tmp)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().startsWith(".download-")).count());
        }
    }

    @Test
    void downloadCountsAgainstTheStorageQuotaAndAsksPerHost() throws Exception {
        var box = new org.kendar.llm.harness.sandbox.LocalDirSandboxExecutor(config);
        WebAgentTools web = new WebAgentTools(config, MAPPER, new StorageQuota(10, box::chatStorage));
        ToolContext ctx = new ToolContext("c1", box.workDir("c1"));

        assertThrows(Exception.class,
                () -> tool(web, "web_download").execute(ctx, json("{'url':'" + ip + "/big','path':'big.bin'}")));
        assertFalse(Files.exists(ctx.workDir().resolve("big.bin")));

        ToolContext denied = ctx.withHosts((h, u) -> false);
        assertThrows(Exception.class,
                () -> tool(web, "web_download").execute(denied, json("{'url':'" + ip + "/bin','path':'a.bin'}")));
        assertFalse(Files.exists(ctx.workDir().resolve("a.bin")));
    }

    // ---- through the harness --------------------------------------------------------------------------------

    private List<AgentEvent> run(AgentHarness h, String chat, boolean grantAnswer) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("approval_request")) {
                h.resolveApproval(chat, ev.data().path("id").asText(), true, null, grantAnswer);
            }
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(20, TimeUnit.SECONDS));
        return events;
    }

    @Test
    void harnessAsksOncePerHostWhenTheUserGrantsItForTheChat() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "web_fetch", "{\"url\":\"" + ip + "/plain\"}"),
                toolUse("t2", "web_fetch", "{\"url\":\"" + ip + "/plain\"}"), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config).build();
        String chat = h.sessions().create(null).chatId();

        List<AgentEvent> events = run(h, chat, true);

        List<AgentEvent> asks = events.stream().filter(e -> e.type().equals("approval_request")).toList();
        assertEquals(1, asks.size());
        assertEquals("host", asks.get(0).data().path("type").asText());
        assertEquals(2, events.stream().filter(e -> e.type().equals("tool_result")
                && e.data().path("output").asText().startsWith("HTTP 200")).count());
    }

    @Test
    void harnessAsksAgainWhenTheAnswerIsOnlyOnce() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "web_fetch", "{\"url\":\"" + ip + "/plain\"}"),
                toolUse("t2", "web_fetch", "{\"url\":\"" + ip + "/plain\"}"), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config).build();

        List<AgentEvent> events = run(h, h.sessions().create(null).chatId(), false);

        assertEquals(2, events.stream().filter(e -> e.type().equals("approval_request")).count());
    }
}

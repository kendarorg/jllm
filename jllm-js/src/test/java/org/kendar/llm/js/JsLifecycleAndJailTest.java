package org.kendar.llm.js;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.sandbox.CwdSandboxExecutor;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.testsupport.HarnessTestSupport.*;

class JsLifecycleAndJailTest {

    @TempDir
    Path base;

    private static ModelResponse jsUse(String id, String js) {
        var content = MAPPER.createArrayNode();
        var use = content.addObject().put("type", "tool_use").put("id", id).put("name", "run_js");
        use.putObject("input").put("code", js);
        return new ModelResponse(content, "tool_use", new Usage(10, 5, 0, 0));
    }

    private static List<AgentEvent> run(AgentHarness h) throws Exception {
        String chat = h.sessions().create(null).chatId();
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("approval_request")) {
                h.resolveApproval(chat, ev.data().path("id").asText(), true, null, false);
            }
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(60, TimeUnit.SECONDS));
        return events;
    }

    private static String firstOutput(List<AgentEvent> events) {
        return events.stream().filter(e -> e.type().equals("tool_result") && !e.data().has("parent"))
                .findFirst().orElseThrow().data().path("output").asText();
    }

    /** A harness the way the CLI builds it: the work dir is the shared project directory. */
    private AgentHarness cliHarness(Path project, ScriptedModel model, String settingsJson) throws Exception {
        var cfg = config(base);
        Path settings = base.resolve("settings.json");
        Files.writeString(settings, settingsJson);
        return AgentHarness.builder().modelClient(model).config(cfg)
                .sandbox(new CwdSandboxExecutor(cfg, project))
                .settings(settings)
                .plugin(new JsPlugin()).build();
    }

    // ---- file jail ------------------------------------------------------------------------------------------

    @Test
    void inTheCliAScriptWorksInItsAgentScratchpadAndCannotSeeProjectFiles() throws Exception {
        Path project = Files.createDirectories(base.resolve("project"));
        Files.writeString(project.resolve("secret.txt"), "project data");
        String js = "tools.write_file({path: 'mine.txt', content: 'x'});"
                + "const r = {};"
                + "try { tools.read_file({path: 'secret.txt'}); r.plain = 'visible'; } catch (e) { r.plain = 'blocked'; }"
                + "try { tools.read_file({path: '../../../../secret.txt'}); r.up = 'visible'; } catch (e) { r.up = 'blocked'; }"
                + "try { tools.read_file({path: '" + project.resolve("secret.txt").toString().replace("\\", "/")
                + "'}); r.abs = 'visible'; } catch (e) { r.abs = 'blocked'; }"
                + "return r;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));
        AgentHarness h = cliHarness(project, model, "{}");

        String out = firstOutput(run(h));

        assertTrue(out.contains("ok: true"), out);
        assertTrue(out.contains("\\\"plain\\\":\\\"blocked\\\"") || out.contains("\"plain\":\"blocked\""), out);
        assertFalse(out.contains("visible"), out);
        assertTrue(out.contains("fileRoot: .kendhar/scratchpad/"), out);
        assertFalse(Files.exists(project.resolve("mine.txt")), "nothing written to the project");
        try (var s = Files.walk(project.resolve(".kendhar/scratchpad"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().equals("mine.txt")));
        }
    }

    @Test
    void withTheJailOffAScriptSeesTheProject() throws Exception {
        Path project = Files.createDirectories(base.resolve("project"));
        Files.writeString(project.resolve("secret.txt"), "project data");
        String js = "return tools.read_file({path: 'secret.txt'});";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));
        AgentHarness h = cliHarness(project, model, "{\"plugins\":{\"js\":{\"enabled\":true,\"settings\":{\"fileJail\":false}}}}");

        String out = firstOutput(run(h));

        assertTrue(out.contains("project data"), out);
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------

    @Test
    void theClockStandsStillWhileAToolCallIsRunning() {
        try (JsSandbox box = new JsSandbox(JsSettings.defaults())) {
            var lib = new ToolLibrary().source(List.of(tool("slow", "G", ToolKind.READ_ONLY, (c, i) -> "")));

            JsResult r = box.run("tools.slow({}); return 'done';", 1, lib, (n, a) -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }, null);

            assertTrue(r.ok(), r.toString());
            assertEquals("\"done\"", r.value());
        }
    }

    @Test
    void aCancelledScriptThatKeepsRunningIsKilledAfterTheGracePeriod() {
        try (JsSandbox box = new JsSandbox(new JsSettings(Long.MAX_VALUE, 120, 1, true))) {
            AtomicBoolean cancelled = new AtomicBoolean();
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                cancelled.set(true);
            }).start();
            long t = System.nanoTime();

            JsResult r = box.run("while (true) {}", 30, null, null, cancelled::get);

            assertFalse(r.ok());
            assertEquals("Cancelled by the user", r.error());
            long ms = (System.nanoTime() - t) / 1_000_000;
            assertTrue(ms >= 1000 && ms < 10_000, "killed after the grace period, took " + ms);
            assertTrue(box.run("return 1;").ok(), "the next call works");
        }
    }

    @Test
    void aKillRequestStopsTheScriptAtOnceWithoutTheGracePeriod() {
        try (JsSandbox box = new JsSandbox(new JsSettings(Long.MAX_VALUE, 120, 60, true))) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicBoolean kill = new AtomicBoolean();
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                kill.set(true);
                cancelled.set(true);
            }).start();
            long t = System.nanoTime();

            JsResult r = box.run("try { while (true) {} } finally { globalThis.cleanup = 1; }", 30, null, null,
                    cancelled::get, kill::get);

            assertFalse(r.ok());
            assertEquals("Cancelled by the user", r.error());
            assertTrue((System.nanoTime() - t) / 1_000_000 < 5_000, "no 60 s grace");
        }
    }

    @Test
    void aGlobalSetInOneCallIsNotVisibleInTheNextCallOfTheSameChat() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", "globalThis.keep = 7; return 1;"),
                jsUse("t2", "return typeof globalThis.keep;"), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).plugin(new JsPlugin()).build();

        List<AgentEvent> events = run(h);

        List<String> outs = events.stream().filter(e -> e.type().equals("tool_result") && !e.data().has("parent"))
                .map(e -> e.data().path("output").asText()).toList();
        assertTrue(outs.get(1).contains("\"undefined\""), outs.toString());
    }

    // ---- end to end -----------------------------------------------------------------------------------------

    @Test
    void aScriptFetchesParsesWritesAFileAndReturnsASummary() throws Exception {
        var pages = tool("fake_fetch", "Web", ToolKind.READ_ONLY, (c, i) -> {
            String url = i.path("x").asText();
            return "{\"title\":\"" + url + "\",\"items\":[{\"n\":\"a\",\"p\":3},{\"n\":\"b\",\"p\":4}]}";
        });
        String js = "const rows = [];"
                + "for (const u of ['page1', 'page2']) {"
                + "  const doc = tools.fake_fetch({x: u});"
                + "  for (const it of doc.items) rows.push(u + ',' + it.n + ',' + it.p);"
                + "}"
                + "tools.write_file({path: 'report.csv', content: rows.join('\\n')});"
                + "const back = tools.read_file({path: 'report.csv'});"
                + "return {rows: rows.length, total: rows.reduce((s, r) => s + Number(r.split(',')[2]), 0),"
                + "        lines: back.split('\\n').length};";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).tools(List.of(pages))
                .plugin(new JsPlugin()).build();

        long t = System.nanoTime();
        String out = firstOutput(run(h));
        long ms = (System.nanoTime() - t) / 1_000_000;

        assertTrue(out.contains("ok: true"), out);
        assertTrue(out.contains("rows") && out.contains("4") && out.contains("14"), out);
        assertTrue(ms < 30_000, "within the default timeout: " + ms + " ms");
        System.out.println("run_js end-to-end test took " + ms + " ms");
    }
}

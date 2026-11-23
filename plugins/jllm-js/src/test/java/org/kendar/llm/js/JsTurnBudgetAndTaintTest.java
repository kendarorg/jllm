package org.kendar.llm.js;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.testsupport.HarnessTestSupport.*;

/** run_js through the agent loop: inner calls spend the turn budget, and a write after untrusted output asks. */
class JsTurnBudgetAndTaintTest {

    @TempDir
    Path base;

    private static ModelResponse jsUse(String id, String js) {
        var content = MAPPER.createArrayNode();
        var use = content.addObject().put("type", "tool_use").put("id", id).put("name", "run_js");
        use.putObject("input").put("code", js);
        return new ModelResponse(content, "tool_use", new Usage(10, 5, 0, 0));
    }

    /** Untrusted output taints the chat. */
    private static HarnessConfig gated(HarnessConfig c) {
        return c.withOutput(new HarnessConfig.Output(30_000, 2_000, "wrap", true, List.of()));
    }

    private AgentHarness harness(ScriptedModel model, HarnessConfig config, List<HarnessTool> tools) {
        return new AgentHarnessBuilder().modelClient(model).config(config).tools(tools).plugin(new JsPlugin()).build();
    }

    /** Runs one turn; {@code answer} decides each approval request. */
    private static List<AgentEvent> run(AgentHarness h, Predicate<AgentEvent> answer) throws Exception {
        String chat = h.sessions().create(null).chatId();
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("approval_request")) {
                h.resolveApproval(chat, ev.data().path("id").asText(), answer.test(ev), null, false, false);
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

    private static List<AgentEvent> approvals(List<AgentEvent> events, String type) {
        return events.stream().filter(e -> e.type().equals("approval_request")
                && e.data().path("type").asText().equals(type)).toList();
    }

    @Test
    void innerCallsUseTheTurnBudgetAndTheScriptIsToldToReturn() throws Exception {
        HarnessTool ping = tool("ping", "G", ToolKind.READ_ONLY, (c, i) -> "pong");
        // two turns, one inner call worth a turn: the first call fits, the second is refused
        HarnessConfig cfg = config(base).withMaxTurns(2)
                .withLoop(new HarnessConfig.Loop(null, null, 1));
        String js = "let ok = 0, msg = '';"
                + "for (let i = 0; i < 4; i++) { try { tools.ping({}); ok++; } catch (e) { msg = e.message; } }"
                + "return {ok, msg};";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("done"));

        List<AgentEvent> events = run(harness(model, cfg, List.of(ping)), e -> true);

        String out = firstOutput(events);
        assertTrue(out.contains("ok: true"), out);
        assertTrue(out.contains("\"ok\":1"), out);
        assertTrue(out.contains("turn budget") && out.contains("return what you have"), out);
    }

    @Test
    void whenTheBudgetIsUsedTheLoopAsksToContinueAndReportsBothCounts() throws Exception {
        HarnessTool ping = tool("ping", "G", ToolKind.READ_ONLY, (c, i) -> "pong");
        HarnessConfig cfg = config(base).withMaxTurns(2).withLoop(new HarnessConfig.Loop(null, null, 1));
        String js = "try { tools.ping({}); tools.ping({}); } catch (e) {} return 1;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("done"));

        List<AgentEvent> events = run(harness(model, cfg, List.of(ping)), e -> true);

        List<AgentEvent> asks = approvals(events, "continue");
        assertEquals(1, asks.size(), events.stream().map(AgentEvent::type).toList().toString());
        String preview = asks.get(0).data().path("preview").asText();
        assertTrue(preview.contains("1 turn and 1 script tool call"), preview);
    }

    @Test
    void zeroInnerCallsPerTurnDisablesTheCounting() throws Exception {
        HarnessTool ping = tool("ping", "G", ToolKind.READ_ONLY, (c, i) -> "pong");
        HarnessConfig cfg = config(base).withMaxTurns(2).withLoop(new HarnessConfig.Loop(null, null, 0));
        String js = "let ok = 0; for (let i = 0; i < 30; i++) { tools.ping({}); ok++; } return ok;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("done"));

        List<AgentEvent> events = run(harness(model, cfg, List.of(ping)), e -> true);

        assertTrue(firstOutput(events).contains("value: 30"), firstOutput(events));
        assertTrue(approvals(events, "continue").isEmpty());
    }

    @Test
    void aWriteAfterAnUntrustedReadAsksAndADenialReachesTheScript() throws Exception {
        HarnessTool fetch = tool("fake_fetch", "Web", ToolKind.READ_ONLY, (c, i) -> "page text");
        String js = "tools.fake_fetch({x: 'a'});"
                + "let r;"
                + "try { tools.write_file({path: 'out.txt', content: 'hi'}); r = 'written'; }"
                + "catch (e) { r = 'blocked'; }"
                + "return r;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("done"));
        AgentHarness h = harness(model, gated(config(base)), List.of(fetch));

        // run_js itself is approved; the write that follows the untrusted read is refused
        List<AgentEvent> events = run(h, e -> e.data().path("title").asText().startsWith("run_js"));

        List<AgentEvent> tool = approvals(events, "tool");
        assertEquals(2, tool.size(), "run_js, then the write: " + tool);
        assertTrue(tool.get(1).data().path("title").asText().startsWith("write_file"), tool.get(1).data().toString());
        assertTrue(firstOutput(events).contains("blocked") || firstOutput(events).contains("ok: false"),
                firstOutput(events));
        try (var s = Files.walk(base)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().equals("out.txt")), "nothing was written");
        }
    }

    @Test
    void readOnlyFileToolsFromAScriptStayFreeAfterAnUntrustedRead() throws Exception {
        HarnessTool fetch = tool("fake_fetch", "Web", ToolKind.READ_ONLY, (c, i) -> "page text");
        String js = "tools.fake_fetch({x: 'a'}); let r; try { tools.read_file({path: 'nothing.txt'}); } catch (e) { r = 'missing'; }"
                + "return r;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("done"));

        List<AgentEvent> events = run(harness(model, gated(config(base)), List.of(fetch)), e -> true);

        assertEquals(1, approvals(events, "tool").size(), "only run_js asked");
        assertTrue(firstOutput(events).contains("missing"), firstOutput(events));
    }
}

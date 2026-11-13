package org.kendar.llm.js;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.testsupport.HarnessTestSupport.*;

class JsPluginTest {

    @TempDir
    Path base;

    private AgentHarness harness(ScriptedModel model) {
        return AgentHarness.builder().modelClient(model).config(config(base)).plugin(new JsPlugin()).build();
    }

    /** A model response calling run_js; built directly because the test helper turns single quotes into double ones. */
    private static org.kendar.llm.harness.llm.ModelResponse jsUse(String id, String js) {
        var content = MAPPER.createArrayNode();
        var use = content.addObject().put("type", "tool_use").put("id", id).put("name", "run_js");
        use.putObject("input").put("code", js);
        return new org.kendar.llm.harness.llm.ModelResponse(content, "tool_use",
                new org.kendar.llm.harness.llm.Usage(10, 5, 0, 0));
    }

    /** Runs one turn; answers every approval request with {@code approve}. */
    private List<AgentEvent> run(AgentHarness h, boolean approve) throws Exception {
        String chat = h.sessions().create(null).chatId();
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("approval_request")) {
                h.resolveApproval(chat, ev.data().path("id").asText(), approve, null, false);
            }
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(60, TimeUnit.SECONDS));
        return events;
    }

    /** Outputs of the calls made by the model, in order. */
    private static List<String> outer(List<AgentEvent> events) {
        return events.stream().filter(e -> e.type().equals("tool_result") && !e.data().has("parent"))
                .map(e -> e.data().path("output").asText()).toList();
    }

    private static List<AgentEvent> approvals(List<AgentEvent> events) {
        return events.stream().filter(e -> e.type().equals("approval_request")).toList();
    }

    @Test
    void runJsIsRegisteredAndBashIsGone() {
        AgentHarness h = harness(new ScriptedModel());

        List<String> names = h.tools().every().stream().map(HarnessTool::name).toList();

        assertTrue(names.contains("run_js"));
        assertFalse(names.contains("bash"));
        assertEquals(ToolKind.OTHER, h.tools().get("run_js").kind());
    }

    @Test
    void aBashToolAddedLaterIsRefused() {
        AgentHarness h = harness(new ScriptedModel());

        assertThrows(IllegalStateException.class, () -> h.tools().setSource("mcp", List.of(
                new SimpleTool("bash", "x", SimpleTool.schema(), "G", ToolKind.OTHER, (c, i) -> ""))));
    }

    @Test
    void collidingNamesAfterTheJsMappingAreRejectedAndTheRegistryIsUnchanged() {
        AgentHarness h = harness(new ScriptedModel());
        int before = h.tools().every().size();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> h.tools().setSource("mcp",
                List.of(tool("my-tool", "G", ToolKind.READ_ONLY, (c, i) -> ""),
                        tool("my_tool", "G", ToolKind.READ_ONLY, (c, i) -> ""))));

        assertTrue(e.getMessage().contains("my-tool") && e.getMessage().contains("my_tool"), e.getMessage());
        assertEquals(before, h.tools().every().size());
    }

    @Test
    void runsASimpleScriptAfterTheUserApprovedIt() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", "return 1+1;"), text("ok"));
        AgentHarness h = harness(model);

        List<AgentEvent> events = run(h, true);

        assertTrue(outer(events).get(0).contains("ok: true") && outer(events).get(0).contains("value: 2"), outer(events).toString());
        assertEquals(1, approvals(events).size());
        assertTrue(approvals(events).get(0).data().path("preview").asText().contains("return 1+1;"),
                "the approval shows the full script");
    }

    @Test
    void aRejectedScriptDoesNotRun() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", "return 1+1;"), text("ok"));

        List<AgentEvent> events = run(harness(model), false);

        assertEquals("The user rejected this tool call", outer(events).get(0));
    }

    @Test
    void fileToolsFromAScriptNeedNoApprovalAndStayInTheChatDirectory() throws Exception {
        String js = "tools.write_file({path: 'out.txt', content: 'hello'});"
                + "const back = tools.read_file({path: 'out.txt'});"
                + "let escaped = 'no';"
                + "try { tools.read_file({path: '../../outside.txt'}); escaped = 'yes'; } catch (e) { escaped = e.message; }"
                + "return {back, escaped};";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));
        AgentHarness h = harness(model);

        List<AgentEvent> events = run(h, true);

        String out = outer(events).get(0);
        assertTrue(out.contains("ok: true"), out);
        assertTrue(out.contains("hello"), out);
        assertFalse(out.contains("\\\"escaped\\\":\\\"yes\\\"") || out.contains("\"escaped\":\"yes\""), out);
        assertEquals(1, approvals(events).size(), "only the run_js approval itself: " + approvals(events));
        assertTrue(out.contains("fileRoot: "), out);
    }

    @Test
    void aWebCallToANewHostAsksAndADenialReachesTheScriptAsAnError() throws Exception {
        String js = "try { tools.web_fetch({url: 'http://blocked.invalid/x'}); return 'fetched'; }"
                + "catch (e) { return 'caught: ' + e.message; }";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));
        AgentHarness h = harness(model);
        String chat = h.sessions().create(null).chatId();
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("approval_request")) {
                boolean host = ev.data().path("type").asText().equals("host");
                h.resolveApproval(chat, ev.data().path("id").asText(), !host, null, false);
            }
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(60, TimeUnit.SECONDS));

        assertEquals(2, approvals(events).size());
        assertEquals("host", approvals(events).get(1).data().path("type").asText());
        assertTrue(outer(events).get(0).contains("caught: Access to host 'blocked.invalid' was denied"), outer(events).toString());
    }

    @Test
    void anUncaughtErrorEndsTheRunWithOkFalse() throws Exception {
        String js = "tools.read_file({path: 'missing.txt'}); return 1;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));

        List<AgentEvent> events = run(harness(model), true);

        assertTrue(outer(events).get(0).contains("ok: false"), outer(events).toString());
        assertTrue(outer(events).get(0).contains("error:"));
    }

    @Test
    void bashAndTheAgentToolsAreNotInTheLibrary() throws Exception {
        String js = "return [typeof tools.bash, typeof tools.run_js, typeof tools.sub_agent, typeof tools.read_file];";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));

        List<AgentEvent> events = run(harness(model), true);

        assertTrue(outer(events).get(0).contains("[\"undefined\",\"undefined\",\"undefined\",\"function\"]"),
                outer(events).toString());
    }

    @Test
    void theToolsObjectIsFrozenAndTheBridgeGlobalIsGone() throws Exception {
        String js = "'use strict'; let r = {};"
                + "try { tools.extra = 1; r.add = 'ok'; } catch (e) { r.add = 'blocked'; }"
                + "try { tools = 5; r.set = 'ok'; } catch (e) { r.set = 'blocked'; }"
                + "r.frozen = Object.isFrozen(tools); r.bridge = typeof __invoke; return r;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));

        List<AgentEvent> events = run(harness(model), true);

        String out = outer(events).get(0);
        assertTrue(out.contains("\\\"add\\\":\\\"blocked\\\"") || out.contains("\"add\":\"blocked\""), out);
        assertTrue(out.contains("\"frozen\":true") && out.contains("\"bridge\":\"undefined\""), out);
    }

    @Test
    void innerCallsAreEventsUnderTheRunJsCall() throws Exception {
        String js = "tools.write_file({path: 'a.txt', content: 'x'}); return 1;";
        ScriptedModel model = new ScriptedModel().enqueue(jsUse("t1", js), text("ok"));

        List<AgentEvent> events = run(harness(model), true);

        assertTrue(events.stream().anyMatch(e -> e.type().equals("tool_call")
                && e.data().path("name").asText().equals("write_file")
                && e.data().path("parent").asText().equals("run_js:t1")));
    }

    @Test
    void libraryIsDeterministicNamedAndCachedByTheToolList() {
        List<HarnessTool> tools = List.of(tool("b-two", "G", ToolKind.READ_ONLY, (c, i) -> ""),
                tool("a_one", "G", ToolKind.READ_ONLY, (c, i) -> ""),
                tool("mcp__docs__search", "Mcp", ToolKind.READ_ONLY, (c, i) -> ""));
        ToolLibrary lib = new ToolLibrary();

        var s1 = lib.source(tools);
        var s2 = lib.source(List.of(tools.get(2), tools.get(1), tools.get(0)));

        assertSame(s1, s2);
        assertEquals(1, lib.cacheSize());
        String text = s1.getCharacters().toString();
        assertTrue(text.contains("\"b_two\": (args) => run(\"b-two\", args)"), text);
        assertTrue(text.contains("\"mcp__docs__search\""));
        assertTrue(text.indexOf("\"a_one\"") < text.indexOf("\"b_two\""), "sorted");

        lib.source(List.of(tools.get(0)));
        assertEquals(2, lib.cacheSize());
    }
}

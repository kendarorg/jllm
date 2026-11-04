package org.kendar.llm.harness.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import static org.kendar.llm.harness.HarnessTestSupport.config;
import static org.kendar.llm.harness.HarnessTestSupport.json;
import static org.kendar.llm.harness.HarnessTestSupport.text;
import static org.kendar.llm.harness.HarnessTestSupport.tool;
import static org.kendar.llm.harness.HarnessTestSupport.toolUse;

class HooksTest {

    @TempDir
    Path base;

    private AgentHarness.Builder builder(ScriptedModel model) {
        return AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools();
    }

    private List<AgentEvent> runTurn(AgentHarness h, String text) throws Exception {
        String chat = h.sessions().create(null).chatId();
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, text);
        assertTrue(done.await(20, TimeUnit.SECONDS));
        return events;
    }

    private static String toolOutput(List<AgentEvent> events) {
        return events.stream().filter(e -> e.type().equals("tool_result")).findFirst().orElseThrow()
                .data().path("output").asText();
    }

    @Test
    void preToolHookDeniesAndToolDoesNotRun() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "act", "{}"), text("ok"));
        AgentHarness h = builder(model)
                .tools(List.of(tool("act", "Misc", ToolKind.READ_ONLY, (c, i) -> { ran.add("x"); return "done"; })))
                .hook(HookEvent.PRE_TOOL, ctx -> HookResult.deny("not today")).build();

        List<AgentEvent> events = runTurn(h, "go");

        assertEquals("Denied by a hook: not today", toolOutput(events));
        assertTrue(ran.isEmpty());
    }

    @Test
    void preToolHookRewritesInputAndPostToolAddsContext() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "echo", "{\"v\":\"a\"}"), text("ok"));
        AgentHarness h = builder(model)
                .tools(List.of(tool("echo", "Misc", ToolKind.READ_ONLY, (c, i) -> "got " + i.path("v").asText())))
                .hook(HookEvent.PRE_TOOL, ctx -> HookResult.modifyInput(json("{'v':'b'}")))
                .hook(HookEvent.POST_TOOL, ctx -> HookResult.addContext("after " + ctx.tool() + ":" + ctx.output()))
                .build();

        String out = toolOutput(runTurn(h, "go"));

        assertTrue(out.startsWith("got b"), out);
        assertTrue(out.contains("after echo:got b"), out);
    }

    @Test
    void promptHookCanBlockAndAddContext() throws Exception {
        ScriptedModel blocked = new ScriptedModel();
        AgentHarness h1 = builder(blocked).hook(HookEvent.USER_PROMPT, ctx -> HookResult.deny("no")).build();
        String chat = h1.sessions().create(null).chatId();
        h1.start(chat, "hi");
        Thread.sleep(300);
        assertTrue(blocked.requests.isEmpty(), "the model must not be called");

        ScriptedModel model = new ScriptedModel().enqueue(text("ok"));
        AgentHarness h2 = builder(model).hook(HookEvent.USER_PROMPT, ctx -> HookResult.addContext("EXTRA")).build();
        runTurn(h2, "hi");
        assertTrue(model.requests.get(0).messages().toString().contains("EXTRA"));
    }

    @Test
    void failingHookIsIgnoredAndStopHookSeesTheAnswer() throws Exception {
        List<String> stops = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(text("final"));
        AgentHarness h = builder(model)
                .hook(HookEvent.USER_PROMPT, ctx -> { throw new IllegalStateException("boom"); })
                .hook(HookEvent.STOP, ctx -> { stops.add(ctx.text()); return HookResult.allow(); }).build();

        runTurn(h, "hi");

        assertEquals(List.of("final"), stops);
    }

    @Test
    void fluentHooksFireInOrderAroundAgentLlmAndTool() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "act", "{}"), text("final"));
        AgentHarness h = builder(model)
                .tools(List.of(tool("act", "Misc", ToolKind.READ_ONLY, (c, i) -> "done")))
                .beforeAgent(ctx -> add(order, "beforeAgent:" + ctx.agent() + ":" + ctx.text()))
                .beforeLlmCall(ctx -> add(order, "beforeLlm:" + ctx.agent()))
                .afterLlmCall(ctx -> add(order, "afterLlm:" + ctx.text() + ":" + ctx.isError()))
                .beforeToolCall(ctx -> add(order, "beforeTool:" + ctx.tool()))
                .afterToolCall(ctx -> add(order, "afterTool:" + ctx.output()))
                .afterAgent(ctx -> add(order, "afterAgent:" + ctx.agent() + ":" + ctx.text())).build();

        runTurn(h, "hi");

        assertEquals(List.of("beforeAgent:main:hi", "beforeLlm:main", "afterLlm::false", "beforeTool:act",
                "afterTool:done", "beforeLlm:main", "afterLlm:final:false", "afterAgent:main:final"), order);
    }

    @Test
    void beforeAgentAndBeforeLlmCallCanDeny() throws Exception {
        ScriptedModel unused = new ScriptedModel().enqueue(text("never"));
        AgentHarness blocked = builder(unused).beforeAgent(ctx -> HookResult.deny("no agent")).build();
        List<AgentEvent> events = runTurn(blocked, "hi");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("warning")
                && e.data().path("message").asText().contains("no agent")));
        assertEquals(0, unused.requests.size());

        ScriptedModel model = new ScriptedModel().enqueue(text("never"));
        AgentHarness noLlm = builder(model).beforeLlmCall(ctx -> HookResult.deny("no llm")).build();
        events = runTurn(noLlm, "hi");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("error")
                && e.data().path("message").asText().contains("no llm")));
        assertEquals(0, model.requests.size());
    }

    @Test
    void deniedSubAgentFailsItsTaskEntry() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .enqueue(toolUse("t1", "task", "{\"prompts\":[\"p\"]}"), text("ok"));
        AgentHarness h = builder(model)
                .beforeAgent(ctx -> HookContext.MAIN.equals(ctx.agent()) ? HookResult.allow() : HookResult.deny("nope"))
                .build();

        String out = toolOutput(runTurn(h, "go"));

        assertTrue(out.contains("failed"), out);
        assertTrue(out.contains("nope"), out);
    }

    private static HookResult add(List<String> order, String entry) {
        order.add(entry);
        return HookResult.allow();
    }

    @Test
    void pluginContributesToolHookAndCommand() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HarnessPlugin plugin = r -> r
                .tool(tool("plug", "Plug", ToolKind.READ_ONLY, (c, i) -> "plugged"))
                .hook(HookEvent.POST_TOOL, ctx -> { seen.add(ctx.tool()); return HookResult.allow(); })
                .command("/deploy", "Deploy it", (chat, arg) -> "deploying " + arg);
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "plug", "{}"), text("ok"));
        AgentHarness h = builder(model).plugin(plugin).build();

        runTurn(h, "go");
        String chat = h.sessions().create(null).chatId();

        assertEquals(List.of("plug"), seen);
        assertTrue(h.commands().handles("/deploy now"));
        assertEquals("deploying now", h.commands().execute(chat, "/deploy now").output());
        assertTrue(h.commands().list().stream().anyMatch(c -> c.name().equals("/deploy")));
    }
}

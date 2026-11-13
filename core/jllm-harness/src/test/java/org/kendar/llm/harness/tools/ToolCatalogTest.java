package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ToolCatalogTest {

    @TempDir
    Path base;

    /** Plugin with a tool run_js that calls {name, input} through the catalog. */
    private static HarnessPlugin caller(List<String> listed) {
        return new HarnessPlugin() {
            @Override
            public String id() {
                return "caller";
            }

            @Override
            public void install(PluginRegistrar r) {
                ToolCatalog catalog = r.catalog();
                r.tool(tool("run_js", "Js", ToolKind.READ_ONLY, (ctx, in) -> {
                    if (in.path("list").asBoolean()) {
                        catalog.tools(ctx).forEach(t -> listed.add(t.name()));
                        return "listed";
                    }
                    try {
                        return "OK:" + catalog.invoke(ctx, in.path("name").asText(), in.path("input"));
                    } catch (ToolCatalog.ToolCallException e) {
                        return "ERR:" + e.getMessage();
                    }
                }));
            }
        };
    }

    private AgentHarness.Builder builder(ScriptedModel model, List<String> listed) {
        return AgentHarness.builder().modelClient(model).config(config(base)).plugin(caller(listed));
    }

    private List<AgentEvent> run(AgentHarness h, String text) throws Exception {
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

    /** Outputs of the calls made by the model (not the inner ones), in order. */
    private static List<String> outer(List<AgentEvent> events) {
        return events.stream().filter(e -> e.type().equals("tool_result") && !e.data().has("parent"))
                .map(e -> e.data().path("output").asText()).toList();
    }

    private static String call(String name, String inputJson) {
        return "{\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    @Test
    void toolsListIsTheAgentListWithoutRefusedOnes() throws Exception {
        List<String> listed = new ArrayList<>();
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "run_js", "{\"list\":true}"), text("ok"));
        AgentHarness h = builder(model, listed).build();

        run(h, "go");

        assertTrue(listed.containsAll(List.of("read_file", "write_file", "web_fetch")), listed.toString());
        assertFalse(listed.contains("bash"));
        assertFalse(listed.contains("run_js"));
    }

    @Test
    void invokeReturnsRawOutputAndHooksSeeTheParent() throws Exception {
        List<String> parents = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t0", "run_js", call("write_file", "{\"path\":\"a.txt\",\"content\":\"hello\"}")),
                toolUse("t1", "run_js", call("read_file", "{\"path\":\"a.txt\"}")), text("ok"));
        AgentHarness h = builder(model, new ArrayList<>())
                .hook(HookEvent.PRE_TOOL, c -> {
                    parents.add(c.tool() + "<-" + c.parent());
                    return HookResult.allow();
                }).build();

        List<AgentEvent> events = run(h, "go");

        assertTrue(outer(events).get(1).startsWith("OK:"), outer(events).toString());
        assertTrue(outer(events).get(1).contains("hello"));
        assertEquals(List.of("run_js<-null", "write_file<-run_js:t0", "run_js<-null", "read_file<-run_js:t1"), parents);
        assertTrue(events.stream().anyMatch(e -> e.type().equals("tool_call")
                && e.data().path("parent").asText().equals("run_js:t1")));
    }

    @Test
    void fileToolsAreFreeAndOtherNonReadOnlyToolsAreGated() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "run_js", call("write_file", "{\"path\":\"out.txt\",\"content\":\"x\"}")),
                toolUse("t2", "run_js", call("act", "{}")), text("ok"));
        AgentHarness h = builder(model, new ArrayList<>())
                .tools(List.of(tool("act", "Misc", ToolKind.OTHER, (c, i) -> {
                    ran.add("x");
                    return "done";
                }))).build();
        String chat = h.sessions().create(null).chatId();
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("approval_request")) {
                h.resolveApproval(chat, ev.data().path("id").asText(), false, null);
            }
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(20, TimeUnit.SECONDS));

        assertTrue(outer(events).get(0).startsWith("OK:"), events.toString());
        assertTrue(ran.isEmpty());
        List<AgentEvent> asks = events.stream().filter(e -> e.type().equals("approval_request")).toList();
        assertEquals(1, asks.size());
        assertEquals("ERR:The user rejected this tool call", outer(events).get(1));
        assertTrue(asks.get(0).data().path("title").asText().startsWith("act"));
    }

    @Test
    void preToolHookCanDenyAnInnerCall() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "run_js", call("read_file", "{\"path\":\"a.txt\"}")), text("ok"));
        AgentHarness h = builder(model, new ArrayList<>())
                .hook(HookEvent.PRE_TOOL, c -> c.parent() != null ? HookResult.deny("no scripts") : HookResult.allow())
                .build();

        assertEquals(List.of("ERR:Denied by a hook: no scripts"), outer(run(h, "go")));
    }

    @Test
    void refusedAndUnknownToolsFail() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "run_js", call("bash", "{\"command\":\"echo\"}")),
                toolUse("t2", "run_js", call("run_js", "{}")),
                toolUse("t3", "run_js", call("sub_agent", "{}")),
                toolUse("t4", "run_js", call("nope", "{}")), text("ok"));
        AgentHarness h = builder(model, new ArrayList<>()).build();

        List<String> outs = outer(run(h, "go"));

        assertEquals(4, outs.size());
        outs.forEach(o -> assertTrue(o.startsWith("ERR:Unknown or unavailable tool"), o));
    }

    @Test
    void invalidArgumentsFail() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "run_js", call("read_file", "{}")), text("ok"));
        AgentHarness h = builder(model, new ArrayList<>()).build();

        assertTrue(outer(run(h, "go")).get(0).startsWith("ERR:Invalid arguments"));
    }

    @Test
    void aToolDisabledWhileTheScriptRunsIsRefusedAtTheNextCall() throws Exception {
        java.util.concurrent.atomic.AtomicReference<AgentHarness> ref = new java.util.concurrent.atomic.AtomicReference<>();
        HarnessPlugin plugin = new HarnessPlugin() {
            @Override
            public String id() {
                return "twice";
            }

            @Override
            public void install(PluginRegistrar r) {
                ToolCatalog catalog = r.catalog();
                r.tool(tool("run_js", "Js", ToolKind.READ_ONLY, (ctx, in) -> {
                    String first = catalog.invoke(ctx, "act", in.path("input"));
                    ref.get().tools().disable(List.of("act"));
                    try {
                        return first + "/" + catalog.invoke(ctx, "act", in.path("input"));
                    } catch (ToolCatalog.ToolCallException e) {
                        return first + "/ERR:" + e.getMessage();
                    }
                }));
            }
        };
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "run_js", "{\"input\":{}}"), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).plugin(plugin)
                .tools(List.of(tool("act", "Misc", ToolKind.READ_ONLY, (c, i) -> "done"))).build();
        ref.set(h);

        assertEquals(List.of("done/ERR:Unknown or unavailable tool 'act'"), outer(run(h, "go")));
    }

    @Test
    void aDenyRuleWithAnArgumentPatternBlocksAnInnerFileWrite() throws Exception {
        Path settings = base.resolve("settings.json");
        java.nio.file.Files.writeString(settings, "{\"permissions\":{\"deny\":[\"write_file(.*secret$)\"]}}");
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "run_js", call("write_file", "{\"path\":\"a.secret\",\"content\":\"x\"}")),
                toolUse("t2", "run_js", call("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}")), text("ok"));
        AgentHarness h = builder(model, new ArrayList<>()).settings(settings).build();

        List<String> outs = outer(run(h, "go"));

        assertTrue(outs.get(0).startsWith("ERR:Denied by permission rule"), outs.toString());
        assertTrue(outs.get(1).startsWith("OK:"), outs.toString());
    }

    @Test
    void inPlanModeAnInnerWriteStaysInTheScratchDirectory() throws Exception {
        ScriptedModel model = new ScriptedModel().enqueue(
                toolUse("t1", "run_js", call("write_file", "{\"path\":\"/etc/escape.txt\",\"content\":\"x\"}")),
                toolUse("t2", "run_js", call("write_file", "{\"path\":\"inside.txt\",\"content\":\"x\"}")), text("ok"));
        AgentHarness h = builder(model, new ArrayList<>()).build();
        String chat = h.sessions().create(null).chatId();
        h.sessions().setMode(chat, org.kendar.llm.harness.permission.PermissionMode.PLAN);
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("approval_request")) h.resolveApproval(chat, ev.data().path("id").asText(), true, null);
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(20, TimeUnit.SECONDS));

        List<String> outs = outer(events);
        assertTrue(outs.get(0).startsWith("ERR:"), outs.toString());
        assertFalse(java.nio.file.Files.exists(Path.of("/etc/escape.txt")));
        assertTrue(outs.get(1).startsWith("OK:"), outs.toString());
    }
}

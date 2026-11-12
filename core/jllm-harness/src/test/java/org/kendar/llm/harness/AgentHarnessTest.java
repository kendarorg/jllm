package org.kendar.llm.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class AgentHarnessTest {

    @TempDir
    Path base;

    private AgentHarness.Builder builder(ScriptedModel model) {
        return AgentHarness.builder().modelClient(model).config(config(base));
    }

    @Test
    void standardToolsAreRegisteredByDefaultAndSorted() {
        AgentHarness h = builder(new ScriptedModel()).build();

        List<String> names = h.tools().all().stream().map(HarnessTool::name).toList();

        assertEquals(12, names.size());
        assertTrue(names.containsAll(List.of("read_file", "write_file", "edit_file", "glob", "ls", "grep", "grep_code", "bash",
                "web_fetch", "web_download", "web_search", "wikipedia_search")));
    }

    @Test
    void withoutStandardToolsRegistersOnlyInjectedOnes() {
        AgentHarness h = builder(new ScriptedModel()).withoutStandardTools()
                .tools(List.of(tool("only", "Misc", ToolKind.READ_ONLY, (c, i) -> ""))).build();

        assertEquals(List.of("only"), h.tools().all().stream().map(HarnessTool::name).toList());
    }

    @Test
    void duplicateToolNamesAreRejected() {
        AgentHarness.Builder b = builder(new ScriptedModel())
                .tools(List.of(tool("read_file", "Mine", ToolKind.READ_ONLY, (c, i) -> "")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, b::build);
        assertTrue(e.getMessage().contains("read_file"));
        assertThrows(IllegalArgumentException.class, () -> new HarnessToolRegistry(List.of(
                tool("a", "G", ToolKind.OTHER, (c, i) -> ""), tool("a", "H", ToolKind.OTHER, (c, i) -> ""))));
    }

    @Test
    void toolOrderIsGroupThenNameAndIndependentOfInjectionOrder() {
        List<HarnessTool> tools = new ArrayList<>(List.of(
                tool("zeta", "B", ToolKind.READ_ONLY, (c, i) -> ""),
                tool("alpha", "B", ToolKind.READ_ONLY, (c, i) -> ""),
                tool("mid", "A", ToolKind.READ_ONLY, (c, i) -> ""),
                tool("nogroup", null, ToolKind.READ_ONLY, (c, i) -> "")));
        List<String> expected = List.of("mid", "alpha", "zeta", "nogroup");

        for (int i = 0; i < 4; i++) {
            Collections.rotate(tools, 1);
            AgentHarness h = builder(new ScriptedModel()).withoutStandardTools().tools(tools).build();
            assertEquals(expected, h.tools().all().stream().map(HarnessTool::name).toList());
        }
    }

    @Test
    void runsWithFakeModelAndToolsWithoutSpring() throws Exception {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(toolUse("t1", "ping", "{}"), text("done"));
        AgentHarness h = builder(model).withoutStandardTools()
                .tools(List.of(tool("ping", "Misc", ToolKind.READ_ONLY, (c, i) -> "pong")))
                .systemPrompt("ADDENDUM").build();
        ChatSessionData chat = h.sessions().create(null);

        CountDownLatch finished = new CountDownLatch(1);
        List<String> types = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat.chatId(), 0, (AgentEvent ev) -> {
            types.add(ev.type());
            if (ev.type().equals("run_finished")) finished.countDown();
        });
        h.start(chat.chatId(), "hello");

        assertTrue(finished.await(20, TimeUnit.SECONDS));
        assertTrue(types.contains("tool_result"));
        assertEquals("hello", h.sessions().get(chat.chatId()).title(), "title is set from the text");
        String system = model.requests.get(0).extra();
        assertTrue(system.contains("coding and investigation agent"), "generic base prompt");
        assertTrue(system.contains("ADDENDUM"));
        assertFalse(system.contains("JLLM"));
        assertFalse(system.contains("Before you say \"done\""), "no small-model rules by default");
        assertEquals(4, h.sessions().messages(h.sessions().get(chat.chatId())).size());
    }

    @Test
    void smallModelAddsItsRulesFromBuilderOrSettings() throws Exception {
        assertTrue(firstSystemPrompt(builder(new ScriptedModel()).smallModel(true)).contains("Before you say \"done\""));
        assertTrue(firstSystemPrompt(builder(new ScriptedModel()).settings(HarnessSettings.empty().withSmallModel(true)))
                .contains("Before you say \"done\""));
    }

    private static String firstSystemPrompt(AgentHarness.Builder b) throws Exception {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("done"));
        AgentHarness h = b.modelClient(model).withoutStandardTools().build();
        ChatSessionData chat = h.sessions().create(null);
        CountDownLatch finished = new CountDownLatch(1);
        h.subscribe(chat.chatId(), 0, ev -> {
            if (ev.type().equals("run_finished")) finished.countDown();
        });
        h.start(chat.chatId(), "hello");
        assertTrue(finished.await(20, TimeUnit.SECONDS));
        return model.requests.get(0).extra();
    }
}

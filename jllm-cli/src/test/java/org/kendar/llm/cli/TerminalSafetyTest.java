package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.agent.AgentEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Model and tool text cannot drive the terminal; idle /steer and /followup become prompts. */
class TerminalSafetyTest {

    private static final String ESC = "\u001b";

    @Test
    void cleanRemovesEscapeSequencesAndControlCharacters() {
        assertEquals("red text", Renderer.clean(ESC + "[31mred" + ESC + "[0m text"));
        assertEquals("before after", Renderer.clean("before " + ESC + "]0;evil title\u0007after"));
        assertEquals("link", Renderer.clean(ESC + "]8;;http://x" + ESC + "\\link" + ESC + "]8;;" + ESC + "\\"));
        assertEquals("a2Jb", Renderer.clean("a\u009b2Jb"), "a C1 CSI is dropped; its parameters stay as text");
        assertEquals("a\tb\nc\r\nd", Renderer.clean("a\tb\nc\r\nd"));
        assertEquals("xy", Renderer.clean("x\ry"), "a bare CR could overwrite the line");
        assertEquals("bell", Renderer.clean("be\u0007ll"));
        assertEquals("", Renderer.clean(null));
    }

    @Test
    void renderedTextAndToolOutputAreCleaned() {
        List<String> out = new ArrayList<>();
        Renderer r = new Renderer(out::add, out::add, false);
        ObjectMapper m = new ObjectMapper();
        ObjectNode text = m.createObjectNode().put("text", "hi" + ESC + "[2J" + ESC + "]0;pwned\u0007");
        r.render(new AgentEvent(1, "text", text));
        ObjectNode result = m.createObjectNode().put("name", "bash").put("output", "ok" + ESC + "[1A" + ESC + "[K")
                .put("isError", false);
        r.render(new AgentEvent(2, "tool_result", result));

        assertEquals("hi", out.get(0));
        assertFalse(out.get(1).contains(ESC), out.get(1));
        assertTrue(out.get(1).endsWith("ok"));
    }

    @Test
    void steerAndFollowupTextIsTakenOnlyWithText() {
        assertEquals("fix it", Main.idleQueuedText("/steer fix it"));
        assertEquals("then test", Main.idleQueuedText("  /followup   then test "));
        assertNull(Main.idleQueuedText("/steer"));
        assertNull(Main.idleQueuedText("/followup   "));
        assertNull(Main.idleQueuedText("/steering x"));
        assertNull(Main.idleQueuedText("/help"));
        assertNull(Main.idleQueuedText("plain prompt"));
    }
}

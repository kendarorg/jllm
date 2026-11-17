package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.agent.AgentEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RendererTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final List<String> out = new ArrayList<>();
    private final List<String> err = new ArrayList<>();

    private Renderer plain() {
        return new Renderer(out::add, err::add, false);
    }

    private Renderer fancy() {
        return new Renderer(out::add, err::add, true);
    }

    private static AgentEvent ev(String type, ObjectNode d) {
        return new AgentEvent(1, type, d);
    }

    private static ObjectNode node() {
        return M.createObjectNode();
    }

    @Test
    void plainToolCallAndResultUseScriptFormat() {
        Renderer r = plain();
        r.render(ev("tool_call", node().put("id", "1").put("name", "bash").set("input", node().put("command", "ls"))));
        r.render(ev("tool_result", node().put("id", "1").put("name", "bash").put("output", "a\nb").put("isError", true)));
        assertEquals("[tool] bash {\"command\":\"ls\"}", out.get(0));
        assertEquals("[result, error] a b", out.get(1));
    }

    @Test
    void plainResultIsAbbreviated() {
        plain().render(ev("tool_result", node().put("id", "1").put("name", "x").put("output", "z".repeat(400))));
        assertEquals("[result] " + "z".repeat(300) + "...", out.get(0));
    }

    @Test
    void errorGoesToErrStream() {
        plain().render(ev("error", node().put("message", "boom")));
        assertTrue(out.isEmpty());
        assertEquals("[error] boom", err.get(0));
    }

    @Test
    void plainModeHasNoColors() {
        plain().render(ev("warning", node().put("message", "careful")));
        plain().render(ev("cancelled", node()));
        assertEquals("[warning] careful", out.get(0));
        assertEquals("[cancelled]", out.get(1));
    }

    @Test
    void fancyModeColorsAndTagsMessages() {
        fancy().render(ev("retry", node().put("message", "again")));
        assertEquals(Renderer.YELLOW + "[retry] again" + Renderer.RESET, out.get(0));
    }

    @Test
    void simpleEventsAreFormatted() {
        Renderer r = plain();
        r.render(ev("plan_ready", node().put("message", "go")));
        r.render(ev("steer_queued", node().put("kind", "followup").put("text", "t")));
        r.render(ev("steer_applied", node().put("text", "t")));
        r.render(ev("login_required", node().put("source", "gh")));
        r.render(ev("stuck", node().put("message", "loop").put("action", "stop")));
        r.render(ev("verify_failed", node().put("attempt", 2).put("message", "red")));
        r.render(ev("unknown_type", node()));
        assertEquals(List.of("[plan] go", "[queued followup] t", "[applied] t",
                "[login] gh is not connected: run /login gh, then ask to retry", "[stuck] loop (stop)",
                "[stop check failed, attempt 2] red"), out);
    }

    @Test
    void fancyToolCallIsCollapsedAndExpandable() {
        Renderer r = fancy();
        r.render(ev("tool_call", node().put("id", "7").put("name", "read").set("input", node().put("path", "a.txt"))));
        assertTrue(out.isEmpty(), "a call is printed with its result");
        r.render(ev("tool_result", node().put("id", "7").put("name", "read").put("output", "l1\nl2\nl3")));
        String block = out.get(0);
        assertTrue(block.contains("read"));
        assertTrue(block.contains("(a.txt)"));
        assertTrue(block.contains("l1"));
        assertTrue(block.contains("+2 lines"));
        assertTrue(block.contains("[/expand 1]"));
        assertFalse(block.contains("l3"));

        r.expand("");
        String full = out.get(1);
        assertTrue(full.contains("l3"));
        assertTrue(full.contains("a.txt"));
    }

    @Test
    void emptyOutputIsMarked() {
        Renderer r = fancy();
        r.render(ev("tool_result", node().put("id", "1").put("name", "t").put("output", "")));
        assertTrue(out.get(0).contains("(no output)"));
    }

    @Test
    void expandHandlesMissingAndUnknownCalls() {
        Renderer r = fancy();
        r.expand("");
        assertEquals("No tool call to expand.", out.get(0));
        r.render(ev("tool_result", node().put("id", "1").put("name", "t").put("output", "o")));
        r.expand("99");
        assertEquals("No such tool call (1..1 kept).", out.get(2));
        r.expand(" 1 ");
        assertTrue(out.get(3).contains("o"));
        r.expand("abc");
        assertTrue(out.get(4).startsWith("No such tool call"));
    }

    @Test
    void oldCallsAreDropped() {
        Renderer r = fancy();
        for (int i = 0; i < 205; i++) {
            r.render(ev("tool_result", node().put("id", "" + i).put("name", "t").put("output", "o")));
        }
        out.clear();
        r.expand("1");
        assertTrue(out.get(0).startsWith("No such tool call (6..205"));
    }

    @Test
    void streamedLinesArePrintedOnceAndFinalTextAddsOnlyTheRest() {
        Renderer r = fancy();
        r.render(ev("text_delta", node().put("block", 0).put("text", "hello ")));
        assertTrue(out.isEmpty(), "no complete line yet");
        r.render(ev("text_delta", node().put("block", 0).put("text", "world\nsecond")));
        assertEquals(Renderer.CYAN + "● " + Renderer.RESET + "hello world", out.get(0));
        r.render(ev("text", node().put("text", "hello world\nsecond line")));
        assertEquals("second line", out.get(1));
    }

    @Test
    void finalTextWithoutStreamingGetsBullet() {
        fancy().render(ev("text", node().put("text", "hi")));
        assertEquals(Renderer.CYAN + "● " + Renderer.RESET + "hi", out.get(0));
    }

    @Test
    void deltasAreIgnoredInPlainMode() {
        plain().render(ev("text_delta", node().put("block", 0).put("text", "a\nb")));
        assertTrue(out.isEmpty());
    }

    @Test
    void textResetAnnouncesDiscardOnlyAfterPrinting() {
        Renderer r = fancy();
        r.render(ev("text_delta", node().put("block", 0).put("text", "x")));
        r.render(ev("text_reset", node()));
        assertTrue(out.isEmpty());
        r.render(ev("text_delta", node().put("block", 0).put("text", "line\n")));
        r.render(ev("text_reset", node()));
        assertTrue(out.get(1).contains("partial answer above is discarded"));
    }

    @Test
    void cancelledAndErrorFlushTheIncompleteLine() {
        Renderer r = fancy();
        r.render(ev("text_delta", node().put("block", 0).put("text", "partial")));
        r.render(ev("cancelled", node()));
        assertTrue(out.get(0).contains("partial"));
        assertTrue(out.get(0).endsWith(" …" + Renderer.RESET));
        assertTrue(out.get(1).contains("[cancelled]"));
    }

    @Test
    void argSummaryPicksTheMostUsefulKeyAndCuts() {
        assertEquals("a.txt", Renderer.argSummary(node().put("path", "a.txt").put("command", "x")));
        assertEquals("ls", Renderer.argSummary(node().put("command", "ls")));
        assertEquals("{\"other\":1}", Renderer.argSummary(node().put("other", 1)));
        String cut = Renderer.argSummary(node().put("path", "p".repeat(200)));
        assertEquals(91, cut.length());
        assertTrue(cut.endsWith("…"));
        assertEquals("a b", Renderer.argSummary(node().put("command", "a\nb")));
    }

    @Test
    void argSummaryStripsEscapes() {
        assertEquals("ls", Renderer.argSummary(node().put("command", "\u001b[31mls")));
    }
}

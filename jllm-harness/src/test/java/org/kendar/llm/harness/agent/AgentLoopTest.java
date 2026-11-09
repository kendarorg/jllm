package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.PartialToolCall;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class AgentLoopTest {

    @TempDir
    Path base;

    Rig rig;
    String chatId;

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chatId = rig.newChat();
    }

    private Events run(String text) {
        Events ev = new Events();
        rig.loop.run(chatId, text, ev, () -> false);
        return ev;
    }

    private ArrayNode stored() {
        return rig.sessions.messages(rig.sessions.get(chatId));
    }

    private static JsonNode lastToolResult(ArrayNode messages, int blockIdx) {
        JsonNode last = messages.get(messages.size() - 1);
        assertEquals("user", last.get("role").asText());
        return last.get("content").get(blockIdx);
    }

    // ---- text-only -------------------------------------------------------------------------------------------

    @Test
    void textOnlyTurnPersistsConversationAndUsage() {
        rig.model.enqueue(text("hello there"));

        Events ev = run("hi");

        assertEquals(1, rig.model.requests.size());
        var req = rig.model.requests.get(0);
        assertEquals("chatId=" + chatId + "; agentId=" + rig.sessions.get(chatId).agentId(), req.pinned());
        assertTrue(req.toolNames().contains("task"), "fan-out tool is offered to the main agent");
        assertEquals(1024, req.maxTokens());
        assertEquals(List.of("usage", "text"), ev.types());
        assertEquals("hello there", ev.ofType("text").get(0).get("text").asText());

        ArrayNode m = stored();
        assertEquals(2, m.size());
        assertEquals("user", m.get(0).get("role").asText());
        assertEquals("hi", m.get(0).get("content").get(0).get("text").asText());
        assertEquals("assistant", m.get(1).get("role").asText());
        assertEquals(10, rig.sessions.get(chatId).inputTokens());
        assertEquals(5, rig.sessions.get(chatId).outputTokens());
    }

    // ---- tool_use -> tool_result -> final ----------------------------------------------------------------------

    @Test
    void toolUseThenToolResultThenFinalAnswer() {
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> "ECHO:" + in.path("x").asText()));
        rig.model.enqueue(toolUse("tu1", "echo", "{\"x\":\"hi\"}"), text("all done"));

        Events ev = run("please echo");

        assertEquals(2, rig.model.requests.size());
        ArrayNode second = rig.model.requests.get(1).messages();
        assertEquals(3, second.size()); // user, assistant(tool_use), user(tool_result)
        JsonNode tr = lastToolResult(second, 0);
        assertEquals("tool_result", tr.get("type").asText());
        assertEquals("tu1", tr.get("tool_use_id").asText());
        assertEquals("ECHO:hi", tr.get("content").asText());
        assertFalse(tr.has("is_error"));

        assertEquals(List.of("usage", "tool_call", "tool_result", "usage", "text"), ev.types());
        assertEquals("ECHO:hi", ev.ofType("tool_result").get(0).get("output").asText());
        assertEquals(4, stored().size());
    }

    @Test
    void unknownToolIsReportedToModelAndLoopContinues() {
        rig.model.enqueue(toolUse("tu1", "does_not_exist", "{}"), text("sorry"));

        Events ev = run("go");

        JsonNode tr = lastToolResult(rig.model.requests.get(1).messages(), 0);
        assertTrue(tr.get("is_error").asBoolean());
        assertTrue(tr.get("content").asText().contains("Unknown tool 'does_not_exist'"));
        assertTrue(ev.ofType("tool_result").get(0).get("isError").asBoolean());
        assertEquals("sorry", ev.ofType("text").get(0).get("text").asText());
    }

    @Test
    void toolExceptionBecomesErrorResult() {
        rig.register(tool("boom", "Misc", ToolKind.READ_ONLY, (c, in) -> {
            throw new IllegalStateException("kaput");
        }));
        rig.model.enqueue(toolUse("tu1", "boom", "{}"), text("ok"));

        run("go");

        JsonNode tr = lastToolResult(rig.model.requests.get(1).messages(), 0);
        assertTrue(tr.get("is_error").asBoolean());
        assertEquals("Error: kaput", tr.get("content").asText());
    }

    @Test
    void emptyAndHugeToolOutputsAreNormalised() {
        rig.register(tool("empty", "Misc", ToolKind.READ_ONLY, (c, in) -> ""));
        rig.register(tool("huge", "Misc", ToolKind.READ_ONLY, (c, in) -> "y".repeat(40_000)));
        rig.model.enqueue(resp("tool_use", "[{'type':'tool_use','id':'a','name':'empty','input':{}},"
                + "{'type':'tool_use','id':'b','name':'huge','input':{}}]"), text("ok"));

        run("go");

        ArrayNode second = rig.model.requests.get(1).messages();
        assertEquals("(no output)", lastToolResult(second, 0).get("content").asText());
        String huge = lastToolResult(second, 1).get("content").asText();
        assertTrue(huge.length() < 31_000);
        assertTrue(huge.contains("[truncated 10000 chars]"));
    }

    @Test
    void parallelToolCallsRunConcurrentlyAndResultsKeepOrder() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        rig.register(tool("slow", "Misc", ToolKind.READ_ONLY, (c, in) -> {
            try {
                barrier.await(5, TimeUnit.SECONDS); // only passes when both run at the same time
            } catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                throw new IllegalStateException("not parallel");
            }
            return "r-" + in.path("x").asText();
        }));
        rig.model.enqueue(resp("tool_use", "[{'type':'text','text':'two calls'},"
                + "{'type':'tool_use','id':'a','name':'slow','input':{'x':'1'}},"
                + "{'type':'tool_use','id':'b','name':'slow','input':{'x':'2'}}]"), text("fin"));

        run("go");

        ArrayNode second = rig.model.requests.get(1).messages();
        JsonNode content = second.get(second.size() - 1).get("content");
        assertEquals(2, content.size());
        assertEquals("a", content.get(0).get("tool_use_id").asText());
        assertEquals("r-1", content.get(0).get("content").asText());
        assertEquals("b", content.get(1).get("tool_use_id").asText());
        assertEquals("r-2", content.get(1).get("content").asText());
    }

    // ---- stop conditions ------------------------------------------------------------------------------------------

    @Test
    void stopsAfterMaxTurns() {
        rig.configure(c -> c.withMaxTurns(3));
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> "r"));
        rig.model.handler = r -> toolUse("t" + r.messages().size(), "echo", "{}");

        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) rig.approvals.resolve(chatId, data.get("id").asText(), false, null);
        };
        rig.loop.run(chatId, "loop forever", ev, () -> false);

        assertEquals(3, rig.model.requests.size());
        assertEquals(1, ev.ofType("approval_request").size());
        assertEquals("continue", ev.ofType("approval_request").get(0).get("type").asText());
        assertEquals(1, ev.ofType("warning").size());
        assertTrue(ev.ofType("warning").get(0).get("message").asText().contains("Stopped after 3 turns"));
    }

    @Test
    void approvingTheQuestionAtTheLimitRunsAnotherBatchOfTurns() {
        rig.configure(c -> c.withMaxTurns(3));
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> "r"));
        rig.model.handler = r -> toolUse("t" + r.messages().size(), "echo", "{}");
        AtomicInteger asked = new AtomicInteger();
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) {
                assertEquals("3", data.get("proposed").asText(), "default is maxTurns");
                rig.approvals.resolve(chatId, data.get("id").asText(), asked.incrementAndGet() == 1, "2");
            }
        };

        rig.loop.run(chatId, "loop forever", ev, () -> false);

        assertEquals(5, rig.model.requests.size()); // 3 turns, then the 2 the user typed
        assertEquals(2, ev.ofType("approval_request").size());
        assertTrue(ev.ofType("warning").get(0).get("message").asText().contains("Stopped after 3 turns"));
    }

    @Test
    void cancelMidLoopStopsBeforeNextModelCall() {
        AtomicBoolean stop = new AtomicBoolean();
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> {
            stop.set(true); // user hits Stop while the tool is running
            return "r";
        }));
        rig.model.enqueue(toolUse("tu1", "echo", "{}"), text("must never be requested"));
        Events ev = new Events();

        rig.loop.run(chatId, "go", ev, stop::get);

        assertEquals(1, rig.model.requests.size());
        assertEquals(1, ev.ofType("cancelled").size());
        assertTrue(ev.ofType("text").isEmpty());
        // tool_use/tool_result pairing stays valid in the persisted log
        ArrayNode m = stored();
        assertEquals("tool_result", m.get(m.size() - 1).get("content").get(0).get("type").asText());
    }

    @Test
    void cancelBeforeStartMakesNoModelCall() {
        Events ev = new Events();

        rig.loop.run(chatId, "go", ev, () -> true);

        assertTrue(rig.model.requests.isEmpty());
        assertEquals(List.of("cancelled"), ev.types());
    }

    @Test
    void toolRequestedAfterCancelIsNotExecuted() {
        AtomicInteger runs = new AtomicInteger();
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> {
            runs.incrementAndGet();
            return "r";
        }));
        AtomicBoolean stop = new AtomicBoolean();
        rig.model.handler = r -> {
            stop.set(true); // cancel arrives while the model is thinking
            return toolUse("tu1", "echo", "{}");
        };

        rig.loop.run(chatId, "go", new Events(), stop::get);

        assertEquals(0, runs.get());
        ArrayNode m = stored();
        assertEquals("Cancelled by user", m.get(m.size() - 1).get("content").get(0).get("content").asText());
    }

    @Test
    void modelFailureEmitsErrorAndEndsRun() {
        Events ev = run("go"); // nothing scripted -> model throws

        assertEquals(1, ev.ofType("error").size());
        assertTrue(ev.ofType("error").get(0).get("message").asText().contains("No scripted"));
    }

    @Test
    void pauseTurnResumesWithoutNewUserMessage() {
        rig.model.enqueue(resp("pause_turn", "[{'type':'text','text':'partial'}]"), text("complete"));

        Events ev = run("go");

        assertEquals(2, rig.model.requests.size());
        assertEquals("assistant", lastRole(rig.model.requests.get(1).messages()));
        assertEquals("complete", ev.ofType("text").get(1).get("text").asText());
    }

    private static String lastRole(ArrayNode m) {
        return m.get(m.size() - 1).get("role").asText();
    }

    @Test
    void maxTokensWithDanglingToolUseKeepsPairingValidAndRetries() {
        rig.model.enqueue(resp("max_tokens", "[{'type':'tool_use','id':'cut','name':'echo','input':{}}]"),
                text("smaller"));

        Events ev = run("go");

        assertEquals(1, ev.ofType("warning").size());
        assertEquals(2, rig.model.requests.size());
        ArrayNode sent = rig.model.requests.get(1).messages();
        JsonNode tr = sent.get(sent.size() - 1).get("content").get(0);
        assertEquals("cut", tr.get("tool_use_id").asText());
        assertTrue(tr.get("is_error").asBoolean());
        assertEquals("smaller", ev.ofType("text").get(0).get("text").asText());
    }

    @Test
    void maxTokensRunsTheCompleteToolCallsAndFailsOnlyTheCutOne() {
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> "ECHO:" + in.path("x").asText()));
        rig.model.enqueue(resp("max_tokens", "[{'type':'tool_use','id':'a','name':'echo','input':{'x':'1'}},"
                        + "{'type':'tool_use','id':'b','name':'echo','input':{'x':'2'}},"
                        + "{'type':'tool_use','id':'cut','name':'echo','input':{}}]"),
                text("done"));

        Events ev = run("go");

        assertEquals(2, ev.ofType("tool_call").size());
        assertEquals(2, rig.model.requests.size());
        ArrayNode sent = rig.model.requests.get(1).messages();
        JsonNode results = sent.get(sent.size() - 1).get("content");
        assertEquals(3, results.size());
        assertEquals("a", results.get(0).get("tool_use_id").asText());
        assertTrue(results.get(0).get("content").asText().contains("ECHO:1"));
        assertFalse(results.get(0).path("is_error").asBoolean());
        assertTrue(results.get(1).get("content").asText().contains("ECHO:2"));
        assertEquals("cut", results.get(2).get("tool_use_id").asText());
        assertTrue(results.get(2).get("is_error").asBoolean());
        assertEquals("done", ev.ofType("text").get(0).get("text").asText());
    }

    @Test
    void cutOffWriteFileKeepsItsCompleteLinesInScratch() throws Exception {
        ArrayNode content = MAPPER.createArrayNode();
        content.addObject().put("type", "tool_use").put("id", "w1").put("name", "write_file").putObject("input")
                .put(PartialToolCall.RAW, "{\"path\": \"src/A.kt\", \"content\": \"l1\\nl2\\npar");
        rig.model.enqueue(new ModelResponse(content, "max_tokens", new Usage(10, 1024, 0, 0)), text("ok"));

        run("go");

        ArrayNode sent = rig.model.requests.get(1).messages();
        String note = sent.get(sent.size() - 1).get("content").get(0).get("content").asText();
        assertTrue(note.contains("output limit of 1024 tokens"), note);
        assertTrue(note.contains("first 2 complete line(s)"), note);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("saved at (.+)\\. They end").matcher(note);
        assertTrue(m.find(), note);
        Path saved = Path.of(m.group(1));
        assertTrue(saved.getFileName().toString().endsWith("-A.kt"));
        assertEquals("l1\nl2\n", java.nio.file.Files.readString(saved));
    }

    @Test
    void maxTokensTwiceInARowStops() {
        rig.model.enqueue(resp("max_tokens", "[{'type':'tool_use','id':'a','name':'echo','input':{}}]"),
                resp("max_tokens", "[{'type':'tool_use','id':'b','name':'echo','input':{}}]"));

        Events ev = run("go");

        assertEquals(2, ev.ofType("warning").size());
        assertEquals(2, rig.model.requests.size());
        ArrayNode m = stored();
        JsonNode tr = m.get(m.size() - 1).get("content").get(0);
        assertEquals("b", tr.get("tool_use_id").asText());
        assertTrue(tr.get("is_error").asBoolean());
    }

    @Test
    void secondUserTurnSeesFirstTurnHistoryAndPersistsMore() {
        rig.model.enqueue(text("one"), text("two"));

        run("first");
        run("second");

        ArrayNode req2 = rig.model.requests.get(1).messages();
        assertEquals(3, req2.size());
        assertEquals("first", req2.get(0).get("content").get(0).get("text").asText());
        assertEquals(4, stored().size());
        assertEquals(20, rig.sessions.get(chatId).inputTokens());
    }

    @Test
    void usageEventsCarryCacheCounters() {
        rig.model.enqueue(resp("end_turn", "[{'type':'text','text':'x'}]", new Usage(1, 2, 300, 40)));

        Events ev = run("go");

        assertEquals(300, ev.ofType("usage").get(0).get("cacheReadTokens").asLong());
        assertEquals(40, rig.sessions.get(chatId).cacheCreationTokens());
    }
}

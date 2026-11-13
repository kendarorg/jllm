package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** The loop notices an agent that repeats itself: nudge first, then ask (interactive) or stop. */
class StuckDetectionTest {

    @TempDir
    Path base;
    Rig rig;
    String chatId;
    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chatId = rig.newChat();
        rig.register(tool("t", "G", ToolKind.READ_ONLY, (c, in) -> "same result"));
        rig.register(tool("u", "G", ToolKind.READ_ONLY, (c, in) -> "other result"));
        rig.register(tool("echo", "G", ToolKind.READ_ONLY, (c, in) -> "got " + in.path("n").asText()));
    }

    private ModelResponse call(String name, String input) {
        return toolUse("c" + seq.incrementAndGet(), name, input);
    }

    /** Answers every approval request (the stuck question) with {@code approve}. */
    private Events answering(boolean approve) {
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) rig.approvals.resolve(chatId, data.get("id").asText(), approve, null);
        };
        return ev;
    }

    private static void assertPaired(ArrayNode messages) {
        for (int i = 0; i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText())) continue;
            Set<String> uses = new HashSet<>();
            for (JsonNode b : m.path("content")) if ("tool_use".equals(b.path("type").asText())) uses.add(b.path("id").asText());
            if (uses.isEmpty()) continue;
            Set<String> answered = new HashSet<>();
            for (JsonNode b : messages.get(i + 1).path("content")) {
                if ("tool_result".equals(b.path("type").asText())) answered.add(b.path("tool_use_id").asText());
            }
            assertTrue(answered.containsAll(uses), "unanswered tool_use in " + messages);
        }
    }

    private List<String> actions(Events ev) {
        return ev.ofType("stuck").stream().map(d -> d.path("action").asText()).toList();
    }

    @Test
    void theSameCallThreeTimesIsNudgedThenStoppedWhenTheUserSaysNo() {
        for (int i = 0; i < 6; i++) rig.model.enqueue(call("t", "{'a':1,'b':2}"));
        rig.model.enqueue(text("never reached"));
        Events ev = answering(false);

        AgentLoop.Outcome outcome = rig.loop.run(chatId, "go", ev, () -> false);

        assertEquals(AgentLoop.Outcome.STUCK, outcome);
        assertEquals(List.of("nudge", "stop"), actions(ev));
        assertEquals("repeat", ev.ofType("stuck").get(0).path("pattern").asText());
        assertEquals(6, rig.model.requests.size());
        ArrayNode log = rig.sessions.messages(rig.sessions.get(chatId));
        assertPaired(log);
        // the nudge is a text block after the tool results of the third call
        JsonNode third = log.get(6);
        JsonNode lastBlock = third.path("content").get(third.path("content").size() - 1);
        assertEquals("text", lastBlock.path("type").asText());
        assertTrue(lastBlock.path("text").asText().startsWith("[harness: loop detected]"), lastBlock.toString());
    }

    @Test
    void theUserCanLetAStuckAgentContinue() {
        for (int i = 0; i < 6; i++) rig.model.enqueue(call("t", "{'a':1}"));
        rig.model.enqueue(text("done"));
        Events ev = answering(true);

        assertEquals(AgentLoop.Outcome.COMPLETED, rig.loop.run(chatId, "go", ev, () -> false));
        assertEquals(List.of("nudge", "continue"), actions(ev));
        assertEquals(1, ev.ofType("approval_request").size());
    }

    @Test
    void keyOrderOfTheInputDoesNotMatter() {
        rig.model.enqueue(call("t", "{'a':1,'b':2}"), call("t", "{'b':2,'a':1}"), call("t", "{'a':1,'b':2}"),
                text("ok"));
        Events ev = answering(false);
        rig.loop.run(chatId, "go", ev, () -> false);
        assertEquals(List.of("nudge"), actions(ev));
    }

    @Test
    void alternationOfTwoStepsIsALoop() {
        for (int i = 0; i < 3; i++) rig.model.enqueue(call("t", "{}"), call("u", "{}"));
        rig.model.enqueue(text("ok"));
        Events ev = answering(false);
        rig.loop.run(chatId, "go", ev, () -> false);
        assertEquals(List.of("nudge"), actions(ev));
        assertEquals("alternation", ev.ofType("stuck").get(0).path("pattern").asText());
    }

    @Test
    void malformedCallsInARowAreALoop() {
        rig.model.enqueue(call("nope", "{'x':1}"), call("nope2", "{'x':2}"), call("nope3", "{'x':3}"), text("ok"));
        Events ev = answering(false);
        rig.loop.run(chatId, "go", ev, () -> false);
        assertEquals(List.of("nudge"), actions(ev));
        assertEquals("malformed", ev.ofType("stuck").get(0).path("pattern").asText());
    }

    @Test
    void distinctCallsAndEditTestCyclesNeverTrigger() {
        for (int i = 0; i < 8; i++) rig.model.enqueue(call("echo", "{'n':" + i + "}"));
        // edit (a different change each time), then the same test
        for (int i = 0; i < 4; i++) rig.model.enqueue(call("echo", "{'edit':" + i + "}"), call("u", "{}"));
        rig.model.enqueue(text("ok"));
        Events ev = answering(false);
        assertEquals(AgentLoop.Outcome.COMPLETED, rig.loop.run(chatId, "go", ev, () -> false));
        assertTrue(ev.ofType("stuck").isEmpty(), ev.ofType("stuck").toString());
    }

    @Test
    void parallelCallsInAnotherOrderAreTheSameStep() {
        for (int i = 0; i < 3; i++) {
            String first = i % 2 == 0 ? "t" : "u";
            String second = i % 2 == 0 ? "u" : "t";
            rig.model.enqueue(resp("tool_use", "[{'type':'tool_use','id':'p" + i + "a','name':'" + first
                    + "','input':{}},{'type':'tool_use','id':'p" + i + "b','name':'" + second + "','input':{}}]"));
        }
        rig.model.enqueue(text("ok"));
        Events ev = answering(false);
        rig.loop.run(chatId, "go", ev, () -> false);
        assertEquals(List.of("nudge"), actions(ev));
        assertEquals("repeat", ev.ofType("stuck").get(0).path("pattern").asText());
    }

    @Test
    void theSameFailingCallIsALoopEvenWithOtherCallsAround() {
        rig.register(tool("fail", "G", ToolKind.READ_ONLY, (c, in) -> { throw new IllegalStateException("boom"); }));
        for (int i = 0; i < 3; i++) {
            rig.model.enqueue(resp("tool_use", "[{'type':'tool_use','id':'f" + i + "','name':'fail','input':{'x':'1'}},"
                    + "{'type':'tool_use','id':'e" + i + "','name':'echo','input':{'n':" + i + "}}]"));
        }
        rig.model.enqueue(text("ok"));
        Events ev = answering(false);
        rig.loop.run(chatId, "go", ev, () -> false);
        assertEquals("same_error", ev.ofType("stuck").get(0).path("pattern").asText());
    }

    @Test
    void disabledDetectionKeepsTodaysBehaviour() {
        rig.configure(c -> c.withLoop(new HarnessConfig.Loop(new HarnessConfig.Stuck(false, 3, 3, 3), null)));
        for (int i = 0; i < 7; i++) rig.model.enqueue(call("t", "{}"));
        rig.model.enqueue(text("ok"));
        Events ev = answering(false);
        assertEquals(AgentLoop.Outcome.COMPLETED, rig.loop.run(chatId, "go", ev, () -> false));
        assertTrue(ev.ofType("stuck").isEmpty());
    }

    @Test
    void aStuckSubAgentReportsStatusStuckToTheParent() {
        for (int i = 0; i < 6; i++) rig.model.enqueue(call("t", "{}"));
        rig.model.enqueue(text("never reached"));
        AgentLoop.SubAgentIncompleteException e = assertThrows(AgentLoop.SubAgentIncompleteException.class,
                () -> rig.loop.runSubAgent(chatId, null, "look around"));
        assertEquals("stuck", e.status());
        assertEquals(6, rig.model.requests.size());
    }

    @Test
    void detectorUnit() {
        StuckDetector d = new StuckDetector(HarnessConfig.Stuck.defaults());
        List<JsonNode> uses = new ArrayList<>();
        uses.add(json("{'type':'tool_use','id':'1','name':'t','input':{'a':1}}"));
        ArrayNode results = MAPPER.createArrayNode();
        results.add(json("{'type':'tool_result','tool_use_id':'1','content':'x'}"));
        assertNull(d.record(uses, results));
        assertNull(d.record(uses, results));
        assertNotNull(d.record(uses, results));
        assertEquals(1, d.detections());
        // reset after a detection: two more are not enough
        assertNull(d.record(uses, results));
        assertNull(d.record(uses, results));
        assertFalse(d.verifyRepeated("a"));
        assertFalse(d.verifyRepeated("b"));
        assertTrue(d.verifyRepeated("b"));
    }
}

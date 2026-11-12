package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.llm.Usage;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.harness.HarnessTestSupport.resp;
import static org.kendar.llm.harness.HarnessTestSupport.text;

class AgentLoopStopReasonTest {

    @TempDir
    Path base;
    Rig rig;
    String chatId;

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chatId = rig.newChat();
    }

    private Events run() {
        Events ev = new Events();
        rig.loop.run(chatId, "hi", ev, () -> false);
        return ev;
    }

    @Test
    void refusalEmitsWarningAndStops() {
        rig.model.enqueue(resp("refusal", "[{\"type\":\"text\",\"text\":\"I cannot help\"}]"));
        Events ev = run();
        assertEquals(1, rig.model.requests.size());
        assertEquals(1, ev.ofType("warning").size());
        assertTrue(ev.ofType("warning").get(0).get("message").asText().contains("refusal"));
    }

    @Test
    void stopSequenceEndsTurnWithoutWarning() {
        rig.model.enqueue(resp("stop_sequence", "[{\"type\":\"text\",\"text\":\"done\"}]"));
        Events ev = run();
        assertEquals(1, rig.model.requests.size());
        assertTrue(ev.ofType("warning").isEmpty());
        assertEquals("done", ev.ofType("text").get(0).get("text").asText());
    }

    @Test
    void pauseTurnResumesWithAssistantContent() {
        rig.model.enqueue(resp("pause_turn", "[{\"type\":\"text\",\"text\":\"part one\"}]"));
        rig.model.enqueue(text("part two"));
        run();
        assertEquals(2, rig.model.requests.size());
    }

    @Test
    void stopWellBelowMaxTokensIsAFullWindowAndIsNotContinued() {
        rig.model.enqueue(resp("max_tokens", "[{\"type\":\"text\",\"text\":\"cut\"}]"));
        Events ev = run();
        assertEquals(1, rig.model.requests.size());
        assertEquals(1, ev.ofType("warning").size());
        assertTrue(ev.ofType("warning").get(0).get("message").asText().contains("context window is full"));
    }

    private static Usage atLimit() {
        return new Usage(10, 1024, 0, 0);
    }

    @Test
    void textCutAtMaxTokensIsContinued() {
        rig.model.enqueue(resp("max_tokens", "[{\"type\":\"text\",\"text\":\"part one \"}]", atLimit()),
                text("part two"));
        Events ev = run();
        assertEquals(2, rig.model.requests.size());
        ArrayNode sent = rig.model.requests.get(1).messages();
        JsonNode more = sent.get(sent.size() - 1);
        assertEquals("user", more.get("role").asText());
        assertTrue(more.get("content").get(0).get("text").asText().startsWith(AgentLoop.CONTINUE_NOTE));
        assertTrue(ev.ofType("warning").get(0).get("message").asText().contains("continue"));
    }

    @Test
    void continuationsStopAfterTheLimit() {
        for (int i = 0; i <= AgentLoop.MAX_CONTINUATIONS; i++) {
            rig.model.enqueue(resp("max_tokens", "[{\"type\":\"text\",\"text\":\"more\"}]", atLimit()));
        }
        Events ev = run();
        assertEquals(AgentLoop.MAX_CONTINUATIONS + 1, rig.model.requests.size());
        assertEquals(AgentLoop.MAX_CONTINUATIONS + 1, ev.ofType("warning").size());
    }
}

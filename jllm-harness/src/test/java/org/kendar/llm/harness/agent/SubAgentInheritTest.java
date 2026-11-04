package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.text;

class SubAgentInheritTest {

    @TempDir
    Path base;

    Rig rig;
    String chat;

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chat = rig.newChat();
    }

    private static void turn(ArrayNode m, String q, String a, int pad) {
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                .put("text", q + " " + "p".repeat(pad));
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", a);
    }

    private void parentLog() {
        ArrayNode m = MAPPER.createArrayNode();
        turn(m, "earlier question", "earlier answer", 0);
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", "current ask");
        var pending = m.addObject();
        pending.put("role", "assistant");
        pending.putArray("content").addObject().put("type", "tool_use").put("id", "task1").put("name", "task")
                .putObject("input");
        rig.sessions.saveMessages(chat, m);
    }

    @Test
    void inheritingTypeStartsFromTheParentCopyWithoutThePendingCall() {
        parentLog();
        String before = rig.sessions.get(chat).messagesJson();
        rig.model.enqueue(text("found"));

        String answer = rig.loop.runSubAgent(chat, "task1", "dig", new AgentDefinition("x", "d", List.of(), "", true, null));

        assertEquals("found", answer);
        ArrayNode sent = rig.model.requests.get(0).messages();
        assertEquals(5, sent.size(), sent.toString());
        assertTrue(sent.get(0).toString().contains("earlier question"));
        assertTrue(sent.get(2).toString().contains("current ask"));
        assertEquals("assistant", sent.get(3).get("role").asText());
        assertTrue(sent.get(4).toString().contains("dig"));
        assertTrue(!sent.toString().contains("tool_use"), "the pending task call is not copied");
        assertEquals(before, rig.sessions.get(chat).messagesJson(), "the parent log is untouched");
    }

    @Test
    void otherTypesStartEmptyAndAnOwnModelIsUsed() {
        parentLog();
        ScriptedModel own = new ScriptedModel();
        own.enqueue(text("mine"));

        String answer = rig.loop.runSubAgent(chat, "task1", "dig", new AgentDefinition("x", "d", List.of(), "", false, own));

        assertEquals("mine", answer);
        assertEquals(1, own.requests.get(0).messages().size());
        assertTrue(rig.model.requests.isEmpty());
    }

    @Test
    void compactionSummarizesOwnMessagesBeforeTheCopy() {
        rig.configure(c -> c.withContextBudgetTokens(1300).withKeepRecentTurns(1));
        ArrayNode m = MAPPER.createArrayNode();
        turn(m, "i1", "a", 1200);
        turn(m, "i2", "a", 1200);
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", "sep");
        int copy = m.size();
        for (int i = 1; i <= 3; i++) turn(m, "own" + i, "a", 800);
        ScriptedModel model = rig.model;
        model.simpleReplies.add("OWN-SUMMARY");
        int[] inherited = {copy};

        assertTrue(rig.contextManager.compactInherited(chat, m, inherited, null, new Events(), u -> { }, null));
        assertEquals(copy, inherited[0], "the copy was big enough to stay once the own part shrank");
        assertTrue(m.get(copy).toString().contains("OWN-SUMMARY"));
        assertEquals(1, model.simpleUserPrompts.size());
        assertTrue(!model.simpleUserPrompts.get(0).contains("i1"));

        // now the own part cannot shrink further: the copy goes too
        rig.configure(c -> c.withContextBudgetTokens(400).withKeepRecentTurns(1));
        rig.model.simpleHandler = user -> user.contains("i1") ? "COPY-SUMMARY" : "OWN2";
        assertTrue(rig.contextManager.compactInherited(chat, m, inherited, null, new Events(), u -> { }, null));
        assertEquals(2, inherited[0]);
        assertTrue(m.get(0).toString().contains("COPY-SUMMARY"));
        assertEquals("assistant", m.get(1).get("role").asText());
    }
}

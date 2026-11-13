package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** Message-log consistency and event guarantees of the loop. */
class AgentLoopRobustnessTest {

    @TempDir
    Path base;
    Rig rig;
    String chatId;

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chatId = rig.newChat();
    }

    /** Every assistant tool_use is answered by a tool_result in the message right after it. */
    private static void assertPaired(ArrayNode messages) {
        for (int i = 0; i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText())) continue;
            Set<String> uses = new HashSet<>();
            for (JsonNode b : m.path("content")) if ("tool_use".equals(b.path("type").asText())) uses.add(b.path("id").asText());
            if (uses.isEmpty()) continue;
            assertTrue(i + 1 < messages.size(), "tool_use without a following message: " + messages);
            Set<String> answered = new HashSet<>();
            for (JsonNode b : messages.get(i + 1).path("content")) {
                if ("tool_result".equals(b.path("type").asText())) answered.add(b.path("tool_use_id").asText());
            }
            assertTrue(answered.containsAll(uses), "unanswered tool_use in " + messages);
        }
    }

    @Test
    void refusalWithToolCallsAnswersEachCallAndTheNextRunIsValid() {
        rig.register(tool("t", "G", ToolKind.OTHER, (c, in) -> "ran"));
        rig.model.enqueue(resp("refusal", "[{'type':'tool_use','id':'u1','name':'t','input':{}},"
                + "{'type':'tool_use','id':'u2','name':'t','input':{}}]"), text("fine"));

        rig.loop.run(chatId, "first", new Events(), () -> false);
        ArrayNode log = rig.sessions.messages(rig.sessions.get(chatId));
        JsonNode last = log.get(log.size() - 1);
        assertEquals("user", last.get("role").asText());
        assertEquals(2, last.get("content").size());
        assertTrue(last.get("content").get(0).get("is_error").asBoolean());

        rig.loop.run(chatId, "second", new Events(), () -> false);
        assertPaired(rig.model.requests.get(1).messages());
    }

    @Test
    void windowExceededWithToolCallsAnswersEachCall() {
        rig.register(tool("t", "G", ToolKind.OTHER, (c, in) -> "ran"));
        rig.model.enqueue(resp("model_context_window_exceeded",
                "[{'type':'tool_use','id':'u1','name':'t','input':{}}]"));

        rig.loop.run(chatId, "first", new Events(), () -> false);

        assertPaired(rig.sessions.messages(rig.sessions.get(chatId)));
    }

    @Test
    void aDanglingToolUseInTheLogIsAnsweredBeforeTheNextRequest() {
        ArrayNode log = new ObjectMapper().createArrayNode();
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", "hi");
        log.addObject().put("role", "assistant").set("content",
                json("[{'type':'tool_use','id':'u9','name':'gone','input':{}}]"));
        rig.sessions.saveMessages(chatId, log);
        rig.model.enqueue(text("ok"));

        rig.loop.run(chatId, "next", new Events(), () -> false);

        ArrayNode sent = rig.model.requests.get(0).messages();
        assertPaired(sent);
        JsonNode fixed = sent.get(2);
        assertEquals("tool_result", fixed.get("content").get(0).get("type").asText());
        assertEquals("next", fixed.get("content").get(1).get("text").asText(), "the user's text follows the results");
        assertPaired(rig.sessions.messages(rig.sessions.get(chatId)));
    }

    @Test
    void aFailingPermissionCheckStillEndsTheCallWithAToolResultEvent() {
        rig.gate = new org.kendar.llm.harness.permission.PermissionGate(t -> {
            throw new IllegalStateException("policy crashed");
        });
        rig.loop = rig.loopWith(rig.model);
        rig.register(tool("t", "G", ToolKind.READ_ONLY, (c, in) -> "ran"));
        rig.model.enqueue(toolUse("u1", "t", "{}"), text("done"));
        Events ev = new Events();

        rig.loop.run(chatId, "go", ev, () -> false);

        assertEquals(1, ev.ofType("tool_call").size());
        assertEquals(1, ev.ofType("tool_result").size());
        assertTrue(ev.ofType("tool_result").get(0).get("isError").asBoolean());
        assertTrue(ev.ofType("tool_result").get(0).get("output").asText().contains("policy crashed"));
    }

    @Test
    void subAgentCutOffIsReportedAsPartialWithItsWarning() throws Exception {
        rig.model.handler = r -> {
            if (r.pinnedSystem().contains("subagent")) {
                return resp("max_tokens", "[{'type':'text','text':'half an answ'}]"); // window full: not continued
            }
            return r.messages().size() == 1 ? toolUse("t1", "task", "{\"prompts\":[\"p\"]}") : text("fin");
        };

        rig.loop.run(chatId, "go", new Events(), () -> false);

        var main = rig.model.requests.stream().filter(r -> !r.pinned().contains("subagent")).toList();
        ArrayNode second = main.get(1).messages();
        JsonNode results = new ObjectMapper().readTree(second.get(second.size() - 1).get("content").get(0)
                .get("content").asText());
        assertEquals("partial", results.get(0).get("status").asText());
        assertEquals("half an answ", results.get(0).get("result").asText());
        assertTrue(results.get(0).get("warning").asText().contains("context window is full"));
        assertEquals("partial", rig.runLog.list(chatId).get(0).status());
    }

    @Test
    void subAgentRefusalIsReportedAsRefused() {
        rig.model.handler = r -> {
            if (r.pinnedSystem().contains("subagent")) return resp("refusal", "[]");
            return text("unused");
        };

        AgentLoop.SubAgentIncompleteException e = assertThrows(AgentLoop.SubAgentIncompleteException.class,
                () -> rig.loop.runSubAgent(chatId, "t1", "do it"));
        assertEquals("refused", e.status());
    }

    @Test
    void subAgentWithoutTheContextStackOnlyGetsTheUsersTools() {
        rig.register(tool("allowed", "G", ToolKind.READ_ONLY, (c, in) -> "a"),
                tool("secret", "G", ToolKind.READ_ONLY, (c, in) -> "s"));
        rig.loop.identity(new org.kendar.llm.harness.auth.Identity(null,
                org.kendar.llm.harness.auth.UserPolicyResolver.single(new org.kendar.llm.harness.auth.UserPolicy(
                        t -> !t.name().equals("secret"), null)),
                null, chat -> null));
        rig.model.handler = r -> text("done");

        rig.loop.runSubAgent(chatId, "t1", "look");

        var sub = rig.model.requests.get(0);
        assertTrue(sub.toolNames().contains("allowed"));
        assertFalse(sub.toolNames().contains("secret"));
    }
}

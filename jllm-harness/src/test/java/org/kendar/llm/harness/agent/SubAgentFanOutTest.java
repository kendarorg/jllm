package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Recorded;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubAgentFanOutTest {

    @TempDir
    Path base;

    private JsonNode taskResults(Rig rig) throws Exception {
        var second = rig.model.requests.stream()
                .filter(r -> !r.pinned().contains("subagent")).toList().get(1).messages();
        JsonNode tr = second.get(second.size() - 1).get("content").get(0);
        assertEquals("tool_result", tr.get("type").asText());
        return new ObjectMapper().readTree(tr.get("content").asText());
    }

    @Test
    void fanOutMergesResultsWhenOneSourceFails() throws Exception {
        Rig rig = new Rig(base);
        rig.register(tool("search", "Misc", ToolKind.READ_ONLY, (c, in) -> "hit-for-" + in.path("x").asText()));
        rig.register(tool("writer", "Misc", ToolKind.FILE_EDIT, (c, in) -> "should never be offered"));
        rig.model.handler = r -> {
            boolean sub = r.pinnedSystem().contains("subagent");
            String first = r.messages().get(0).toString();
            if (!sub) {
                return r.messages().size() == 1
                        ? toolUse("t1", "task", "{\"prompts\":[\"repo A\",\"repo BOOM\",\"repo C\"]}")
                        : text("merged");
            }
            if (first.contains("BOOM")) throw new IllegalStateException("source B is down");
            return r.messages().size() == 1
                    ? toolUse("s", "search", "{\"x\":\"q\"}")
                    : text("answer from " + (first.contains("repo A") ? "A" : "C"));
        };
        String chatId = rig.newChat();
        Events ev = new Events();

        rig.loop.run(chatId, "investigate all repos", ev, () -> false);

        JsonNode results = taskResults(rig);
        assertEquals(3, results.size());
        assertEquals("repo A", results.get(0).get("prompt").asText());
        assertEquals("ok", results.get(0).get("status").asText());
        assertEquals("answer from A", results.get(0).get("result").asText());
        assertEquals("failed", results.get(1).get("status").asText(), "sub-agent model failure must surface as failed");
        assertTrue(results.get(1).get("result").asText().contains("source B is down"));
        assertEquals("ok", results.get(2).get("status").asText());
        assertEquals("answer from C", results.get(2).get("result").asText());
        assertEquals("merged", ev.ofType("text").get(0).get("text").asText());
        // the failed sub-agent did not break the main turn
        assertTrue(ev.ofType("error").isEmpty());
    }

    private Rig taskRig(String type, PermissionMode mode) {
        Rig rig = new Rig(base);
        rig.register(tool("search", "Misc", ToolKind.READ_ONLY, (c, in) -> "x"));
        rig.register(tool("writer", "Misc", ToolKind.FILE_EDIT, (c, in) -> "x"));
        rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> "x"));
        rig.model.handler = r -> {
            if (r.pinnedSystem().contains("subagent")) return text("done");
            return r.messages().size() == 1
                    ? toolUse("t1", "task", "{\"prompts\":[\"p\"],\"subagent_type\":\"" + type + "\"}") : text("fin");
        };
        return rig;
    }

    private static Recorded subRequest(Rig rig) {
        return rig.model.requests.stream().filter(r -> r.pinned().contains("subagent")).findFirst().orElseThrow();
    }

    @Test
    void readOnlyTypeGetsReadOnlyToolsOnlyAndNoTaskTool() {
        Rig rig = taskRig("plan", PermissionMode.EDIT);
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.EDIT);

        rig.loop.run(chatId, "go", new Events(), () -> false);

        var main = rig.model.requests.stream().filter(r -> !r.pinned().contains("subagent")).findFirst().orElseThrow();
        var sub = subRequest(rig);
        assertTrue(main.toolNames().containsAll(List.of("search", "writer", "bash", "task")));
        assertEquals(List.of("search"), sub.toolNames());
        assertEquals("chatId=" + chatId + "; subagent", sub.pinned());
        assertTrue(sub.extra().contains("Mode: PLAN"), "a read-only type runs as PLAN whatever the parent mode");
        assertTrue(sub.extra().contains("Your tools are read-only"));
    }

    @Test
    void writableTypeGetsEveryToolInTheParentMode() {
        Rig rig = taskRig("general", PermissionMode.EDIT);
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.EDIT);

        rig.loop.run(chatId, "go", new Events(), () -> false);

        var sub = subRequest(rig);
        assertEquals(List.of("search", "writer", "bash"), sub.toolNames());
        assertTrue(sub.extra().contains("Mode: EDIT"));
        assertFalse(sub.extra().contains("read-only"), "no read-only claim for a writable type");
    }

    @Test
    void shellCallOfSubAgentAsksTheUserOnTheParentStream() {
        Rig rig = new Rig(base);
        rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> "ran"));
        rig.requiresApproval.put("bash", true);
        rig.model.handler = r -> {
            if (r.pinnedSystem().contains("subagent")) {
                return r.messages().size() == 1 ? toolUse("s", "bash", "{}") : text("sub done");
            }
            return r.messages().size() == 1 ? toolUse("t1", "task", "{\"prompts\":[\"p\"]}") : text("fin");
        };
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.EDIT);
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) rig.approvals.resolve(chatId, data.get("id").asText(), true, null, true);
        };

        rig.loop.run(chatId, "go", ev, () -> false);

        assertEquals(1, ev.ofType("approval_request").size());
        assertTrue(ev.ofType("approval_request").get(0).get("title").asText().startsWith("[general #1] bash"));
        assertTrue(rig.approvals.granted(chatId, "bash"), "a remembered grant covers the whole chat");
        assertFalse(rig.approvals.granted("other-chat", "bash"));
    }

    @Test
    void rejectedSubAgentCallFailsWithTheUsualMessage() {
        Rig rig = new Rig(base);
        rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> "ran"));
        rig.requiresApproval.put("bash", true);
        rig.model.handler = r -> {
            if (r.pinnedSystem().contains("subagent")) {
                return r.messages().size() == 1 ? toolUse("s", "bash", "{}") : text("sub done");
            }
            return r.messages().size() == 1 ? toolUse("t1", "task", "{\"prompts\":[\"p\"]}") : text("fin");
        };
        String chatId = rig.newChat();
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) rig.approvals.resolve(chatId, data.get("id").asText(), false, null);
        };

        rig.loop.run(chatId, "go", ev, () -> false);

        var subMessages = subRequests(rig).get(1).messages();
        assertTrue(subMessages.get(subMessages.size() - 1).toString().contains("The user rejected this tool call"));
    }

    private static List<Recorded> subRequests(Rig rig) {
        return rig.model.requests.stream().filter(r -> r.pinned().contains("subagent")).toList();
    }

    @Test
    void subAgentsDoNotPersistIntoTheChatLog() {
        Rig rig = new Rig(base);
        rig.model.handler = r -> {
            if (r.pinnedSystem().contains("subagent")) return text("sub answer");
            return r.messages().size() == 1 ? toolUse("t1", "task", "{\"prompts\":[\"p\",\"q\"]}") : text("fin");
        };
        String chatId = rig.newChat();

        rig.loop.run(chatId, "go", new Events(), () -> false);

        // user, assistant(tool_use), user(tool_result), assistant(final)
        assertEquals(4, rig.sessions.messages(rig.sessions.get(chatId)).size());
        assertEquals(40, rig.sessions.get(chatId).inputTokens(), "2 main-agent + 2 sub-agent calls are billed to the chat");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolLevelMergeReportsPerPromptFailureWithoutAbortingOthers() throws Exception {
        AgentLoop loop = mock(AgentLoop.class);
        when(loop.runSubAgent(any(), any(), eq("a"), any(), any(), any(), any())).thenReturn("A!");
        when(loop.runSubAgent(any(), any(), eq("b"), any(), any(), any(), any())).thenThrow(new IllegalStateException("b exploded"));
        when(loop.runSubAgent(any(), any(), eq("c"), any(), any(), any(), any())).thenReturn("C!");
        SubAgentTool tool = new SubAgentTool(() -> loop, MAPPER);

        String out = tool.execute(new ToolContext("c1", base), json("{'prompts':['a','b','','c',5]}"));

        JsonNode r = MAPPER.readTree(out);
        assertEquals(3, r.size(), "blank and non-string prompts are dropped");
        assertEquals("A!", r.get(0).get("result").asText());
        assertEquals("failed", r.get(1).get("status").asText());
        assertEquals("b exploded", r.get(1).get("result").asText());
        assertEquals("C!", r.get(2).get("result").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolRejectsEmptyAndCapsPromptCount() throws Exception {
        AgentLoop loop = mock(AgentLoop.class);
        when(loop.runSubAgent(any(), any(), any(), any(), any(), any(), any())).thenReturn("ok");
        SubAgentTool tool = new SubAgentTool(() -> loop, MAPPER);
        ToolContext ctx = new ToolContext("c1", base);

        assertThrows(IllegalArgumentException.class, () -> tool.execute(ctx, json("{'prompts':[]}")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(ctx, json("{}")));

        StringBuilder sb = new StringBuilder("{'prompts':[");
        for (int i = 0; i < 12; i++) sb.append(i == 0 ? "" : ",").append("'p").append(i).append("'");
        sb.append("]}");
        assertEquals(8, MAPPER.readTree(tool.execute(ctx, json(sb.toString()))).size());
        assertEquals(ToolKind.READ_ONLY, tool.kind());
    }
}

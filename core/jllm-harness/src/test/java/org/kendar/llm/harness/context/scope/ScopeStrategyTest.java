package org.kendar.llm.harness.context.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.ContextView;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.state.InMemoryStateStore;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private final TodoStore todos = new TodoStore(new InMemoryStateStore(), MAPPER);

    private ScopeStrategy strategy(int window) {
        HarnessConfig cfg = HarnessConfig.defaults().withContextBudgetTokens(window).withMaxTokens(100);
        ModelClient model = mock(ModelClient.class);
        when(model.simpleComplete(any(), any(), anyInt(), any())).thenReturn("SUMMARY");
        when(model.simpleComplete(any(), any(), anyInt())).thenReturn("SUMMARY");
        ContextManager cm = new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
        return new ScopeStrategy(cfg, cm, MAPPER, todos, new ScopeStrategy.Options(1000, 200, 100, 2, 100),
                (chat, agent) -> dir, Path::toString);
    }

    private static void user(ArrayNode log, String text) {
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void assistantToolUse(ArrayNode log, String id) {
        var c = log.addObject().put("role", "assistant").putArray("content");
        c.addObject().put("type", "tool_use").put("id", id).put("name", "bash").putObject("input");
    }

    private static void toolResult(ArrayNode log, String id, String text) {
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", id).put("content", text);
    }

    private static ContextTurn turn(ArrayNode log) {
        return new ContextTurn("c1", "main", log, null, null, null, null, null, null, true);
    }

    @Test
    void offloadsLargeOldToolResultsAndKeepsHeadAndTail() throws Exception {
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "first request");
        assistantToolUse(log, "t1");
        toolResult(log, "t1", "HEAD" + "x".repeat(20_000) + "TAIL");
        user(log, "second request");

        ContextView v = strategy(2_000).prepare(turn(log));

        assertTrue(v.logChanged());
        String shown = log.get(2).path("content").get(0).path("content").asText();
        assertTrue(shown.startsWith("[Output offloaded to "), shown);
        assertTrue(shown.contains("HEAD") && shown.endsWith("TAIL"));
        assertTrue(shown.length() < 1_000);
        assertEquals(20_008, Files.readString(dir.resolve("offload").resolve("t1.txt")).length());
    }

    @Test
    void doesNothingWhileTheRequestFits() {
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "hello");
        assertFalse(strategy(100_000).prepare(turn(log)).logChanged());
    }

    @Test
    void firstMessageSurvivesSummarization() {
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "THE ORIGINAL TASK");
        for (int i = 0; i < 6; i++) {
            var a = log.addObject().put("role", "assistant").putArray("content");
            a.addObject().put("type", "text").put("text", "answer " + i + " " + "y".repeat(400));
            user(log, "follow up " + i + " " + "z".repeat(400));
        }
        ScopeStrategy s = strategy(1_000);
        ContextView v = s.prepare(turn(log));

        assertTrue(v.logChanged());
        assertEquals("THE ORIGINAL TASK", log.get(0).path("content").get(0).path("text").asText());
        assertEquals("assistant", log.get(1).path("role").asText());
        assertTrue(log.get(2).path("content").get(0).path("text").asText().contains("SUMMARY"));
        // the next compaction keeps the pin and does not duplicate the acknowledgement
        for (int i = 0; i < 6; i++) {
            var a = log.addObject().put("role", "assistant").putArray("content");
            a.addObject().put("type", "text").put("text", "more " + i + " " + "y".repeat(400));
            user(log, "again " + i + " " + "z".repeat(400));
        }
        s.prepare(turn(log));
        assertEquals("THE ORIGINAL TASK", log.get(0).path("content").get(0).path("text").asText());
        int acks = 0;
        for (JsonNode m : log) if ("Understood.".equals(m.path("content").path(0).path("text").asText())) acks++;
        assertEquals(1, acks);
    }

    @Test
    void todoListIsAddedToTheRequestOnly() {
        todos.replace("c1", "main", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                .put("content", "write tests").put("status", "in_progress")));
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "go");
        ContextView v = strategy(100_000).prepare(turn(log));
        String last = v.messages().get(0).path("content").toString();
        assertTrue(last.contains("<todo_list>") && last.contains("write tests"), last);
        assertEquals(1, log.get(0).path("content").size(), "the stored log is untouched");
    }

    @Test
    void todoToolsReplaceAndValidate() throws Exception {
        List<HarnessTool> tools = new ScopeStrategy(HarnessConfig.defaults(), null, MAPPER, todos,
                ScopeStrategy.Options.defaults(), (c, a) -> dir, Path::toString).tools();
        HarnessTool write = tools.stream().filter(t -> t.name().equals("todo_write")).findFirst().orElseThrow();
        HarnessTool read = tools.stream().filter(t -> t.name().equals("todo_read")).findFirst().orElseThrow();
        ToolContext ctx = new ToolContext("c1", dir);

        write.execute(ctx, MAPPER.readTree("{\"todos\":[{\"content\":\"a\",\"status\":\"completed\"},"
                + "{\"content\":\"b\",\"status\":\"in_progress\"}]}"));
        assertTrue(read.execute(ctx, MAPPER.createObjectNode()).contains("[~] 2. b"));

        assertThrows(IllegalArgumentException.class, () -> write.execute(ctx, MAPPER.readTree(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"b\",\"status\":\"in_progress\"}]}")));
        // the failed write left the previous list alone
        assertTrue(read.execute(ctx, MAPPER.createObjectNode()).contains("b"));
        write.execute(ctx, MAPPER.readTree("{\"todos\":[]}"));
        assertEquals("The todo list is empty.", read.execute(ctx, MAPPER.createObjectNode()));
    }

    @Test
    void overflowForcesAMoreAggressivePass() {
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "first");
        assistantToolUse(log, "t1");
        toolResult(log, "t1", "r".repeat(500));
        user(log, "second");
        assistantToolUse(log, "t2");
        toolResult(log, "t2", "q".repeat(500));
        ContextView v = strategy(1_000_000).onOverflow(turn(log));
        assertNotNull(v);
        // the forced pass offloads first, then clears and summarizes: the full text is still on disk
        assertTrue(Files.exists(dir.resolve("offload").resolve("t1.txt")));
        assertTrue(v.logChanged());
    }
}

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** One long turn, offload that pays off, the answer reserve, a sub-agent's task, where offloaded results go. */
class ScopeStrategyTurnTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private final List<String> prompts = new ArrayList<>();

    private ContextManager manager(HarnessConfig cfg) {
        ModelClient model = mock(ModelClient.class);
        when(model.simpleComplete(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            prompts.add(inv.getArgument(1));
            return "SUMMARY";
        });
        return new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
    }

    private ScopeStrategy strategy(HarnessConfig cfg, ScopeStrategy.Options options) {
        return new ScopeStrategy(cfg, manager(cfg), MAPPER, new TodoStore(new InMemoryStateStore(), MAPPER), options,
                (chat, agent) -> dir, Path::toString);
    }

    private static HarnessConfig config(int window) {
        return HarnessConfig.defaults().withContextBudgetTokens(window).withMaxTokens(100);
    }

    private static void user(ArrayNode log, String text) {
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void assistant(ArrayNode log, String text) {
        log.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void rounds(ArrayNode log, int n, int resultChars) {
        for (int i = 0; i < n; i++) {
            log.addObject().put("role", "assistant").putArray("content").addObject().put("type", "tool_use")
                    .put("id", "t" + i).put("name", "bash").putObject("input");
            log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                    .put("tool_use_id", "t" + i).put("content", "r".repeat(resultChars));
        }
    }

    private static String text(JsonNode msg) {
        return msg.path("content").path(0).path("text").asText();
    }

    private static String result(ArrayNode log, int i) {
        return log.get(i).get("content").get(0).get("content").asText();
    }

    private static ContextTurn turn(ArrayNode log) {
        return new ContextTurn("c1", "main", log, null, null, null, null, null, null, true);
    }

    @Test
    void anOverflowInsideOneLongTurnIsRecovered() {
        ScopeStrategy s = strategy(config(4000), new ScopeStrategy.Options(100_000, 100_000, 100, 2, 0));
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "the request");
        rounds(log, 30, 1500);
        String last = log.get(log.size() - 1).toString();

        ContextView v = s.onOverflow(turn(log));

        assertNotNull(v, "nothing was done about the turn in progress");
        assertEquals(1, prompts.size());
        assertEquals("the request", text(log.get(0)));
        assertTrue(text(log.get(1)).endsWith("SUMMARY"));
        assertEquals(last, log.get(log.size() - 1).toString());
        assertEquals(3, log.size());
    }

    @Test
    void aLongTurnOverTheBudgetIsCutBeforeTheProviderRefusesIt() {
        ScopeStrategy s = strategy(config(8000), new ScopeStrategy.Options(100_000, 100_000, 100, 2, 0));
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "the request");
        rounds(log, 30, 1500); // ~14k tokens

        ContextView v = s.prepare(turn(log));

        assertTrue(v.logChanged());
        assertEquals("the request", text(log.get(0)));
        assertTrue(ScopeStrategy.estimate(log, null, null) <= s.budget(null));
    }

    @Test
    void offloadingNeverMakesAResultLarger() throws Exception {
        ScopeStrategy s = strategy(config(100_000), ScopeStrategy.Options.defaults());
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "the request");
        rounds(log, 1, 2100);
        String before = log.toString();

        assertNull(s.onOverflow(turn(log)), "a result just over the threshold gains nothing from offloading");
        assertEquals(before, log.toString());
        assertFalse(Files.exists(dir.resolve("offload")), "nothing is written for a result that stays");

        ArrayNode big = MAPPER.createArrayNode();
        user(big, "the request");
        rounds(big, 1, 8000);
        assertNotNull(s.onOverflow(turn(big)));
        String pointer = result(big, 2);
        assertTrue(pointer.startsWith(ScopeStrategy.OFFLOADED));
        assertTrue(pointer.length() < 1000 + 300, "500 characters at each end and the header: " + pointer.length());
        assertEquals(8000, Files.readString(dir.resolve("offload").resolve("t0.txt")).length());
    }

    @Test
    void theBudgetLeavesRoomForTheWholeAnswer() {
        HarnessConfig cfg = HarnessConfig.defaults().withContextBudgetTokens(200_000).withMaxTokens(32_000);
        assertEquals(200_000 - 32_000 - 4_000, strategy(cfg, ScopeStrategy.Options.defaults()).budget(null));
        // an explicit cap below maxTokens
        assertEquals(200_000 - 8_000 - 4_000,
                strategy(cfg, new ScopeStrategy.Options(20_000, 2_000, 1_500, 2, 8_000)).budget(null));
        // never more than a quarter of a small window
        HarnessConfig tiny = HarnessConfig.defaults().withContextBudgetTokens(8_000).withMaxTokens(4_096);
        assertEquals(8_000 - 2_000 - 160, strategy(tiny, ScopeStrategy.Options.defaults()).budget(null));
    }

    @Test
    void aSubAgentKeepsItsTaskAndTheCopyBoundary() {
        ScopeStrategy s = strategy(config(4000), new ScopeStrategy.Options(100_000, 100_000, 100, 2, 0));
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "PARENT-REQUEST");
        assistant(log, "a".repeat(8000));
        user(log, "parent follow-up");
        assistant(log, "b".repeat(8000)); // the copy alone is over the budget
        user(log, "CHILD-TASK");
        rounds(log, 12, 1500);
        int[] inherited = {4};

        ContextView v = s.onOverflow(new ContextTurn("c1", "sub", log, inherited, null, null, null, null, null, false));

        assertNotNull(v);
        assertEquals("CHILD-TASK", text(log.get(inherited[0])), "the copy boundary no longer points at the task");
        assertEquals("user", log.get(inherited[0]).get("role").asText());
        assertEquals(2, inherited[0], "the copy was summarized into a user/assistant pair");
        assertFalse(log.toString().contains("PARENT-REQUEST"));
        assertTrue(ScopeStrategy.estimate(log, null, null) <= s.budget(null), "the agent's own rounds were left as they were");
    }

    @Test
    void offloadedResultsGoThroughTheSink() {
        HarnessConfig cfg = config(100_000);
        List<String> stored = new ArrayList<>();
        ScopeStrategy s = new ScopeStrategy(cfg, manager(cfg), MAPPER, new TodoStore(new InMemoryStateStore(), MAPPER),
                ScopeStrategy.Options.defaults(), (chat, agent, name, text) -> {
            stored.add(chat + "/" + agent + "/" + name + ":" + text.length());
            return "/workspace/.jllm/scratchpad/" + agent + "/offload/" + name;
        });
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "the request");
        rounds(log, 1, 8000);

        assertNotNull(s.onOverflow(turn(log)));

        assertEquals(List.of("c1/main/t0.txt:8000"), stored);
        assertTrue(result(log, 2).startsWith(ScopeStrategy.OFFLOADED + "/workspace/.jllm/scratchpad/main/offload/t0.txt"));
        assertFalse(Files.exists(dir.resolve("offload")));
    }
}

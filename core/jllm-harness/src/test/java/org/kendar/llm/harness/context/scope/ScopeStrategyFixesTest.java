package org.kendar.llm.harness.context.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.state.InMemoryStateStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Fixed part counted once, thinking dropped after a rewrite, summaries made before results are cleared. */
class ScopeStrategyFixesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private final List<String> prompts = new ArrayList<>();

    private ScopeStrategy strategy(HarnessConfig cfg, ScopeStrategy.Options options) {
        ModelClient model = mock(ModelClient.class);
        when(model.simpleComplete(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            prompts.add(inv.getArgument(1));
            return "SUMMARY";
        });
        when(model.simpleComplete(any(), any(), anyInt())).thenAnswer(inv -> {
            prompts.add(inv.getArgument(1));
            return "SUMMARY";
        });
        ContextManager cm = new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
        return new ScopeStrategy(cfg, cm, MAPPER, new TodoStore(new InMemoryStateStore(), MAPPER), options,
                (chat, agent) -> dir, Path::toString);
    }

    private ScopeStrategy strategy(int window) {
        return strategy(HarnessConfig.defaults().withContextBudgetTokens(window).withMaxTokens(100),
                new ScopeStrategy.Options(1000, 200, 100, 2, 100));
    }

    private static void user(ArrayNode log, String text) {
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
    }

    private static void round(ArrayNode log, String id, String result, boolean thinking) {
        ArrayNode c = log.addObject().put("role", "assistant").putArray("content");
        if (thinking) c.addObject().put("type", "thinking").put("thinking", "t").put("signature", "sig");
        c.addObject().put("type", "tool_use").put("id", id).put("name", "bash").putObject("input");
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", id).put("content", result);
    }

    private static ContextTurn turn(ArrayNode log, RequestPrefix prefix) {
        return new ContextTurn("c1", "main", log, null, null, prefix, null, null, null, true);
    }

    private static boolean hasThinking(ArrayNode log) {
        for (JsonNode m : log) for (JsonNode b : m.path("content")) if ("thinking".equals(b.path("type").asText())) return true;
        return false;
    }

    @Test
    void theBudgetDoesNotSubtractTheFixedPart() {
        ScopeStrategy s = strategy(10_000);
        RequestPrefix big = new RequestPrefix("P".repeat(20_000), "E", List.of(), null, false);
        assertEquals(s.budget(null), s.budget(big));
        assertEquals(10_000 - 100 - 200, s.budget(null));
    }

    @Test
    void aRequestThatFitsWithItsFixedPartCountedOnceIsLeftAlone() {
        // 5000 tokens of system text + ~3000 of messages = 8000 <= 9700; counted twice it was over 4700
        ScopeStrategy s = strategy(10_000);
        RequestPrefix prefix = new RequestPrefix("P".repeat(20_000), "", List.of(), null, false);
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "first");
        round(log, "t1", "x".repeat(12_000), false);
        user(log, "second");
        String before = log.toString();

        assertFalse(s.prepare(turn(log, prefix)).logChanged());
        assertEquals(before, log.toString());
    }

    @Test
    void anOverBudgetRequestIsStillShrunkWhenTheFixedPartTipsIt() {
        ScopeStrategy s = strategy(10_000);
        RequestPrefix prefix = new RequestPrefix("P".repeat(30_000), "", List.of(), null, false); // 7500 tokens
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "first");
        round(log, "t1", "x".repeat(12_000), false); // ~3000
        user(log, "second");

        assertTrue(s.prepare(turn(log, prefix)).logChanged());
    }

    @Test
    void thinkingIsDroppedOnceEarlierContentWasRewritten() {
        ScopeStrategy s = strategy(2_000);
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "first request");
        round(log, "t1", "HEAD" + "x".repeat(20_000) + "TAIL", true);
        user(log, "second request");
        round(log, "t2", "short", true);

        assertTrue(s.prepare(turn(log, null)).logChanged());
        assertFalse(hasThinking(log), log.toString());
    }

    @Test
    void deferOnThinkingOverflowWaitsForTheTurnToEnd() {
        HarnessConfig cfg = HarnessConfig.defaults().withContextBudgetTokens(2_000).withMaxTokens(100)
                .withCompaction(new HarnessConfig.Compaction(true, 15_000, null, true));
        ScopeStrategy s = strategy(cfg, new ScopeStrategy.Options(1000, 200, 100, 2, 100));
        ArrayNode log = MAPPER.createArrayNode();
        user(log, "first request");
        round(log, "t1", "x".repeat(20_000), true);
        String before = log.toString();

        assertFalse(s.prepare(turn(log, null)).logChanged());
        assertEquals(before, log.toString());
    }

    @Test
    void theSummaryIsMadeFromToolResultsBeforeTheyAreCleared() {
        // results under the offload threshold, over the clearing threshold: step 2 clears, step 3 summarizes
        ScopeStrategy s = strategy(HarnessConfig.defaults().withContextBudgetTokens(1_500).withMaxTokens(100)
                .withKeepRecentTurns(1), new ScopeStrategy.Options(100_000, 100_000, 100, 2, 100));
        ArrayNode log = MAPPER.createArrayNode();
        for (int i = 1; i <= 5; i++) {
            user(log, "request " + i + " " + "p".repeat(800));
            round(log, "t" + i, "RESULT-" + i + " " + "z".repeat(900), false);
        }

        assertTrue(s.prepare(turn(log, null)).logChanged());

        assertFalse(prompts.isEmpty(), "the summary step ran");
        assertTrue(prompts.get(0).contains("RESULT-2"), prompts.get(0));
        assertFalse(prompts.get(0).contains("[tool result cleared"), prompts.get(0));
    }
}

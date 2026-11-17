package org.kendar.llm.eval.grade.judge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.eval.grade.CheckResult;
import org.kendar.llm.eval.grade.TrialContext;
import org.kendar.llm.eval.run.TrialTrace;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class LlmJudgeTest {

    @TempDir
    Path cache;

    static final class Stub implements ModelClient {
        final List<String> replies;
        final List<String> prompts = new ArrayList<>();

        Stub(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            prompts.add(user);
            return replies.get(Math.min(prompts.size() - 1, replies.size() - 1));
        }
    }

    private static TrialContext ctx(String answer) {
        return new TrialContext("c", new TrialTrace(List.of(), List.of(), answer, null, 0), Path.of("."), Map.of(),
                List.of("What is in a.txt?"), Map.of());
    }

    private static final String GOOD = "{\"score\": 5, \"pass\": true, \"reason\": \"grounded\"}";

    @Test
    void parsesStrictVerdictAndRecordsJudge() {
        Stub s = new Stub(GOOD);
        LlmJudge j = new LlmJudge(s, "claude-haiku-5-5", false, cache);
        CheckResult r = j.judge(ctx("42"), Rubrics.GROUNDED_CORRECTNESS);
        assertEquals(CheckResult.Status.PASS, r.status());
        assertTrue(r.reason().contains("judge=claude-haiku-5-5"), r.reason());
        assertFalse(r.reason().contains("self-graded"));
        assertTrue(s.prompts.get(0).contains("grounded-correctness"));
        assertTrue(s.prompts.get(0).contains("What is in a.txt?"));
    }

    @Test
    void mapsLowMediumHigh() {
        assertEquals(1, LlmJudge.parseBody("{\"score\":\"low\",\"pass\":false,\"reason\":\"r\"}").score());
        assertEquals(3, LlmJudge.parseBody("{\"score\":\"Medium\",\"pass\":false,\"reason\":\"r\"}").score());
        assertEquals(5, LlmJudge.parseBody("```json\n{\"score\":\"high\",\"pass\":true,\"reason\":\"r\"}\n```").score());
    }

    @Test
    void rejectsMalformedShapes() {
        for (String bad : List.of("", "sure!", "{\"score\":7,\"pass\":true,\"reason\":\"r\"}",
                "{\"score\":3,\"pass\":\"yes\",\"reason\":\"r\"}", "{\"score\":3,\"pass\":true}",
                "Here: {\"score\":3,\"pass\":true,\"reason\":\"r\"}")) {
            assertThrows(IllegalArgumentException.class, () -> LlmJudge.parseBody(bad), bad);
        }
    }

    @Test
    void retriesOnceOnMalformedOutput() {
        Stub s = new Stub("I think it is fine", GOOD);
        LlmJudge j = new LlmJudge(s, "j", false, cache);
        CheckResult r = j.judge(ctx("42"), Rubrics.RECALL);
        assertEquals(CheckResult.Status.PASS, r.status());
        assertEquals(2, s.prompts.size());
        assertTrue(s.prompts.get(1).contains("rejected"));
    }

    @Test
    void twoMalformedRepliesAreNotMeasurable() {
        Stub s = new Stub("nope", "still nope");
        LlmJudge j = new LlmJudge(s, "j", false, cache);
        CheckResult r = j.judge(ctx("42"), Rubrics.RECALL);
        assertEquals(CheckResult.Status.NOT_MEASURABLE, r.status());
        assertEquals(2, s.prompts.size());
    }

    @Test
    void cachesByInputHashAcrossInstances() {
        Stub s = new Stub(GOOD);
        new LlmJudge(s, "j", false, cache).judge(ctx("42"), Rubrics.RECALL);
        CheckResult again = new LlmJudge(s, "j", false, cache).judge(ctx("42"), Rubrics.RECALL);
        assertEquals(1, s.prompts.size());
        assertTrue(again.reason().contains("cached"), again.reason());
        new LlmJudge(s, "j", false, cache).judge(ctx("43"), Rubrics.RECALL);
        assertEquals(2, s.prompts.size());
        new LlmJudge(s, "other-judge", false, cache).judge(ctx("42"), Rubrics.RECALL);
        assertEquals(3, s.prompts.size());
    }

    @Test
    void malformedOutputIsNotCached() {
        Stub s = new Stub("bad", "bad", GOOD);
        LlmJudge j = new LlmJudge(s, "j", false, cache);
        assertEquals(CheckResult.Status.NOT_MEASURABLE, j.judge(ctx("42"), Rubrics.RECALL).status());
        assertEquals(CheckResult.Status.PASS, j.judge(ctx("42"), Rubrics.RECALL).status());
    }

    @Test
    void recordsSelfGradedFlag() {
        Stub s = new Stub("{\"score\":2,\"pass\":false,\"reason\":\"wrong\"}");
        CheckResult r = new LlmJudge(s, "j", true, cache).judge(ctx("42"), Rubrics.ESCALATION);
        assertEquals(CheckResult.Status.FAIL, r.status());
        assertTrue(r.reason().contains("self-graded"), r.reason());
        JudgeVerdict v = new LlmJudge(s, "j", true, cache).grade(Rubrics.ESCALATION, List.of("q"), "t", "a");
        assertTrue(v.selfGraded());
        assertEquals("j", v.judge());
    }

    @Test
    void everyRubricIsComplete() {
        for (Rubric r : Rubrics.all()) {
            assertSame(r, Rubrics.byName(r.name()));
            assertFalse(r.low().isBlank() || r.medium().isBlank() || r.high().isBlank(), r.name());
        }
        assertEquals(7, Rubrics.all().size());
        assertThrows(IllegalArgumentException.class, () -> Rubrics.byName("nope"));
    }

    @Test
    void factCheckAdapterUsesFactMatchRubric() {
        Stub s = new Stub(GOOD);
        var v = new LlmJudge(s, "j", false, cache).judge("how many?", "42", "forty-two");
        assertTrue(v.correct());
        assertTrue(s.prompts.get(0).contains("Expected fact: 42"));
    }
}

package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StepRunnerTest {

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private final List<String> log = new ArrayList<>();
    private RunRecord rec;

    private StepRunner runner(Registry reg, String flowJson) {
        FlowDef flow = FlowDef.parse(flowJson.replace('\'', '"'));
        rec = new RunRecord("run-1234567890", "u", flow.doc(), Json.M.createObjectNode());
        FlowContext ctx = new FlowContext(rec.runId, rec.user, rec.input);
        RunStore store = new RunStore.Memory();
        store.save(rec);
        return new StepRunner(reg, store, FlowLibrary.empty(), rec, flow, ctx,
                (step, kind, detail, data) -> log.add(step + ":" + kind), c -> fail("no children"));
    }

    private static final String FLOW = "{'name':'f','start':'a','steps':{'a':{'type':'action','action':'x','next':'e'},"
            + "'e':{'type':'finish'}}}";

    @Test
    void nullActionResultBecomesNullNode() {
        StepRunner r = runner(new Registry().action("x", c -> null), FLOW);
        assertTrue(r.runStep("a", "a#1", j("{'type':'action','action':'x'}")).isNull());
    }

    @Test
    void temporaryFailureIsRetriedThenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        StepRunner r = runner(new Registry().action("x", c -> {
            if (n.incrementAndGet() < 3) throw new IllegalStateException("boom");
            return j("{'ok':true}");
        }), FLOW);
        JsonNode out = r.runStep("a", "a#1", j("{'type':'action','action':'x'}"));
        assertTrue(out.get("ok").asBoolean());
        assertEquals(3, n.get());
        assertEquals(2, log.stream().filter(l -> l.equals("a:retry")).count());
    }

    @Test
    void failureAfterRetriesCarriesAttempts() {
        StepRunner r = runner(new Registry().action("x", c -> {
            throw new IllegalStateException("boom");
        }), FLOW);
        StepRunner.StepFailure f = assertThrows(StepRunner.StepFailure.class,
                () -> r.runStep("a", "a#1", j("{'type':'action','action':'x','retries':1}")));
        assertEquals(2, f.attempts);
        assertEquals(FlowException.Kind.ACTION, f.error.kind());
        assertEquals("a", f.step);
    }

    @Test
    void unknownActionIsPermissionErrorWithoutRetry() {
        StepRunner r = runner(new Registry(), FLOW);
        StepRunner.StepFailure f = assertThrows(StepRunner.StepFailure.class,
                () -> r.runStep("a", "a#1", j("{'type':'action','action':'missing'}")));
        assertEquals(FlowException.Kind.PERMISSION, f.error.kind());
        assertEquals(1, f.attempts);
    }

    @Test
    void unknownStepTypeIsInvalidFlow() {
        StepRunner r = runner(new Registry(), FLOW);
        StepRunner.StepFailure f = assertThrows(StepRunner.StepFailure.class,
                () -> r.runStep("a", "a#1", j("{'type':'bogus'}")));
        assertEquals(FlowException.Kind.INVALID_FLOW, f.error.kind());
    }

    @Test
    void unansweredQuestionFromActionWaits() {
        StepRunner r = runner(new Registry().action("x", c -> c.ask(Question.text("q", "Name?"))), FLOW);
        StepRunner.WaitSignal w = assertThrows(StepRunner.WaitSignal.class,
                () -> r.runStep("a", "a#1", j("{'type':'action','action':'x','waitMs':1000}")));
        assertEquals("a#1/q", w.question.key());
        assertTrue(w.deadline > 0);
        assertNull(w.childRunId);
    }

    @Test
    void answerForReturnsRecordedAnswerAndTimeoutMarker() {
        StepRunner r = runner(new Registry(), FLOW);
        rec.answers.set("q1", Json.M.createObjectNode().put("value", "v"));
        rec.answers.set("q2", Json.M.createObjectNode().put("timeout", true));
        assertEquals("v", r.answerFor("k", "a", j("{}"), Question.text("q1", "t")).get("value").asText());
        assertNull(r.answerFor("k", "a", j("{}"), Question.text("q2", "t")));
        assertThrows(StepRunner.WaitSignal.class, () -> r.answerFor("k", "a", j("{}"), Question.text("q3", "t")));
    }

    @Test
    void answerForEnforcesQuestionLimit() {
        StepRunner r = runner(new Registry(), FLOW);
        JsonNode step = j("{'questions':1}");
        rec.answers.set("q1", Json.M.createObjectNode().put("value", "v"));
        rec.answers.set("q2", Json.M.createObjectNode().put("value", "v"));
        r.answerFor("k", "a", step, Question.text("q1", "t"));
        FlowException e = assertThrows(FlowException.class,
                () -> r.answerFor("k", "a", step, Question.text("q2", "t")));
        assertEquals(FlowException.Kind.LIMIT, e.kind());
    }

    @Test
    void renderOfMissingTemplateIsEmptyObject() {
        StepRunner r = runner(new Registry(), FLOW);
        assertTrue(r.render(Json.M.missingNode()).isObject());
        assertEquals(5, r.render(j("5")).asInt());
    }

    @Test
    void taskToolsAbsentTextAndList() {
        assertNull(StepRunner.taskTools(j("{}")));
        assertEquals(List.of("*"), StepRunner.taskTools(j("{'tools':'*'}")));
        assertEquals(List.of("a", "b"), StepRunner.taskTools(j("{'tools':['a','b']}")));
    }

    @Test
    void looksLikeQuestionHeuristic() {
        assertTrue(StepRunner.looksLikeQuestion("Which one?"));
        assertTrue(StepRunner.looksLikeQuestion("Done. Please confirm the name"));
        assertFalse(StepRunner.looksLikeQuestion("All done."));
        assertFalse(StepRunner.looksLikeQuestion("   "));
    }

    @Test
    void modelWithoutGatewayIsPermissionError() {
        StepRunner r = runner(new Registry(), "{'name':'f','start':'a','tasks':{'t':{'agent':'ag','prompt':'p'}},"
                + "'steps':{'a':{'type':'task','task':'t','next':'e'},'e':{'type':'finish'}}}");
        StepRunner.StepFailure f = assertThrows(StepRunner.StepFailure.class,
                () -> r.runStep("a", "a#1", j("{'type':'task','task':'t'}")));
        assertEquals(FlowException.Kind.PERMISSION, f.error.kind());
    }
}

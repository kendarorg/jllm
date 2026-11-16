package org.kendar.llm.workflows;

import org.kendar.llm.workflows.api.WorkflowApi.QuestionKind;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WaitingTest {
    @TempDir
    Path dir;

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    private static final String REVIEW = """
            {'name':'review','start':'pay','steps':{
              'pay':{'type':'action','action':'charge','next':'op'},
              'op':{'type':'decide','by':'user','question':'Approve?','who':'operator',
                    'outcomes':{'approve':'note','reject':'no'}},
              'note':{'type':'ask','kind':'text','text':'Note for {{input.who}}','pattern':'number','next':'end'},
              'no':{'type':'finish','result':{'r':'rejected'}},
              'end':{'type':'finish','result':{'r':'approved','note':'{{steps.note}}','paid':'{{steps.pay.id}}'}}}}""";

    @Test
    void runStopsRestartsAndFinishesWithoutRepeatingSideEffects() {
        AtomicInteger charges = new AtomicInteger();
        Registry reg = new Registry().action("charge", c -> {
            charges.incrementAndGet();
            return j("{'id':'" + c.idempotencyKey().length() + "'}");
        });
        var e1 = new FlowEngine(reg, new RunStore.Files(dir), FlowLibrary.empty());
        var r1 = e1.run(flow(REVIEW), j("{'who':'Ann'}"), "u1");
        assertEquals(FlowEngine.Status.WAITING, r1.status());
        assertEquals(QuestionKind.SINGLE, r1.pending().kind());
        assertEquals("operator", r1.pending().who());

        // "restart": brand new engine and store over the same directory
        var e2 = new FlowEngine(reg, new RunStore.Files(dir), FlowLibrary.empty());
        assertEquals(1, e2.store().waiting("u1").size());
        assertTrue(e2.store().waiting("u2").isEmpty(), "nobody else sees the run");

        assertThrows(FlowException.class, () -> e2.resume(r1.runId(), j("'maybe'"), "op"));
        assertEquals(RunRecord.WAITING, e2.store().load(r1.runId()).get().status, "refused answer keeps it open");

        var r2 = e2.resume(r1.runId(), j("'approve'"), "op");
        assertEquals(FlowEngine.Status.WAITING, r2.status());
        assertEquals(QuestionKind.TEXT, r2.pending().kind());

        var e3 = new FlowEngine(reg, new RunStore.Files(dir), FlowLibrary.empty());
        assertThrows(FlowException.class, () -> e3.resume(r1.runId(), j("'abc'"), "ann"));
        var r3 = e3.resume(r1.runId(), j("'42'"), "ann");
        assertEquals(FlowEngine.Status.COMPLETED, r3.status());
        assertEquals("42", r3.result().path("note").asText());
        assertEquals(1, charges.get(), "completed step must not run again");
        assertTrue(r3.context().decisions().get(0).decidedBy().startsWith("user:op"));
        assertEquals(RunRecord.COMPLETED, e3.store().load(r1.runId()).get().status);
    }

    @Test
    void javaActionAndChooserAskQuestionsAndAreRerun() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        Registry reg = new Registry().action("gather", c -> {
            runs.incrementAndGet();
            String name = c.askText("name", "Name?");
            String how = c.askSingle("how", "How?", List.of(new Question.Option("cash", "Cash"),
                    new Question.Option("credit", "Store credit")));
            JsonNode tags = c.askMulti("tags", "Tags?", List.of(new Question.Option("a", "A"),
                    new Question.Option("b", "B"), new Question.Option("c", "C")), 1, 2);
            effects.incrementAndGet(); // side effect only after the last question
            return j("{'name':'" + name + "','how':'" + how + "','tags':" + tags + "}");
        }).chooser("pick", c -> c.askSingle("go", "Go?", List.of(new Question.Option("yes", "Yes"))));
        var f = flow("""
                {'name':'q','start':'g','steps':{
                 'g':{'type':'action','action':'gather','next':'d'},
                 'd':{'type':'decide','by':'java','chooser':'pick','outcomes':{'yes':'end'}},
                 'end':{'type':'finish','result':{'n':'{{steps.g.name}}','h':'{{steps.g.how}}','t':'{{steps.g.tags}}'}}}}""");
        var e = FlowEngine.simple(reg);
        var r = e.run(f, j("{}"), "u");
        assertEquals("name", r.pending().key().substring(r.pending().key().indexOf('/') + 1));
        r = e.resume(r.runId(), j("'Bob'"), "u");
        assertEquals(QuestionKind.SINGLE, r.pending().kind());
        final String id = r.runId();
        assertThrows(FlowException.class, () -> e.resume(id, j("'wire'"), "u"));
        r = e.resume(id, j("'cash'"), "u");
        assertEquals(QuestionKind.MULTI, r.pending().kind());
        assertThrows(FlowException.class, () -> e.resume(id, j("[]"), "u"), "min 1");
        assertThrows(FlowException.class, () -> e.resume(id, j("['a','b','c']"), "u"), "max 2");
        assertThrows(FlowException.class, () -> e.resume(id, j("['zzz']"), "u"));
        r = e.resume(id, j("['a','c']"), "u");
        assertEquals(1, effects.get());
        assertEquals(4, runs.get()); // first run + one rerun per answer
        assertEquals(QuestionKind.SINGLE, r.pending().kind()); // the chooser's question
        r = e.resume(id, j("'yes'"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertEquals("Bob", r.result().path("n").asText());
        assertEquals(1, effects.get());
        assertEquals(4, runs.get(), "the finished step is replayed from the journal");
    }

    @Test
    void questionLimitRaisesError() {
        Registry reg = new Registry().action("many", c -> {
            c.askText("a", "a");
            c.askText("b", "b");
            return null;
        });
        var f = flow("""
                {'name':'l','start':'m','steps':{'m':{'type':'action','action':'many','next':'end','questions':1},
                 'end':{'type':'finish'}}}""");
        var e = FlowEngine.simple(reg);
        var r = e.run(f, j("{}"), "u");
        r = e.resume(r.runId(), j("'x'"), "u");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertEquals("LIMIT", r.error().path("kind").asText());
        // zero questions: the step may not ask at all
        var zero = flow("""
                {'name':'z','start':'m','steps':{'m':{'type':'action','action':'many','next':'end','questions':0},
                 'end':{'type':'finish'}}}""");
        assertEquals(FlowEngine.Status.FAILED, e.run(zero, j("{}"), "u").status());
    }

    @Test
    void waitingTimeTakesTimeoutPathOrFails() {
        Registry reg = new Registry();
        var withPath = flow("""
                {'name':'t','start':'q','steps':{
                 'q':{'type':'ask','kind':'text','text':'?','waitMs':50,'onTimeout':'late','next':'end'},
                 'late':{'type':'finish','result':{'r':'timeout'}},
                 'end':{'type':'finish','result':{'r':'answered'}}}}""");
        var e = FlowEngine.simple(reg);
        var r = e.run(withPath, j("{}"), "u");
        assertEquals(0, e.expire(System.currentTimeMillis()).size(), "not yet");
        var out = e.expire(System.currentTimeMillis() + 1000);
        assertEquals(1, out.size());
        assertEquals("timeout", out.get(0).result().path("r").asText());
        assertEquals(RunRecord.COMPLETED, e.store().load(r.runId()).get().status);

        var noPath = flow(withPath.doc().toString().replace(",\"onTimeout\":\"late\"", ""));
        var r2 = e.run(noPath, j("{}"), "u");
        var out2 = e.expire(System.currentTimeMillis() + 1000);
        assertEquals(FlowEngine.Status.FAILED, out2.get(0).status());
        assertEquals("TIMEOUT", out2.get(0).error().path("kind").asText());
        assertEquals(r2.runId(), out2.get(0).runId());
    }

    @Test
    void cancelIsItsOwnEndStateAndBlocksResume() {
        var f = flow("""
                {'name':'c','start':'q','steps':{'q':{'type':'ask','kind':'text','text':'?','next':'end'},
                 'end':{'type':'finish'}}}""");
        var e = FlowEngine.simple(new Registry());
        var r = e.run(f, j("{}"), "u");
        e.cancel(r.runId());
        assertEquals(RunRecord.CANCELLED, e.store().load(r.runId()).get().status);
        assertThrows(FlowException.class, () -> e.resume(r.runId(), j("'x'"), "u"));
    }

    @Test
    void failedStepIsNotRepeatedWhileHandlerWaits() {
        AtomicInteger booms = new AtomicInteger();
        Registry reg = new Registry().action("boom", c -> {
            booms.incrementAndGet();
            throw new Exception("down");
        });
        var f = flow("""
                {'name':'h','start':'s','limits':{'retries':1},'onError':{'start':'ask','steps':{
                  'ask':{'type':'decide','by':'user','question':'What now?','outcomes':{'ok':'rec','no':'bad'}},
                  'rec':{'type':'finish','outcome':'recovered'},'bad':{'type':'finish','outcome':'rethrow'}}},
                 'steps':{'s':{'type':'action','action':'boom','next':'end'},'end':{'type':'finish'}}}""");
        var e = FlowEngine.simple(reg);
        var r = e.run(f, j("{}"), "u");
        assertEquals(FlowEngine.Status.WAITING, r.status());
        assertEquals(2, booms.get());
        r = e.resume(r.runId(), j("'ok'"), "operator");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertTrue(r.result().path("recovered").asBoolean());
        assertEquals(2, booms.get(), "the recorded failure is replayed, not re-executed");
    }
}

package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    // ---- phase 1: task with Java action around it, enforced shape

    private static final String TASK_FLOW = """
            {'name':'t','start':'a',
             'tasks':{'classify':{'agent':'ag','prompt':'Customer {{steps.a_before.name}} says {{input.msg}}',
                      'answer':{'label':'string','score?':'number'},'before':'load','after':'check'}},
             'steps':{'a':{'type':'task','task':'classify','next':'end','retries':1},
                      'end':{'type':'finish','result':{'label':'{{steps.a.label}}'}}}}""";

    @Test
    void taskRunsWithBeforeAndAfterActions() {
        List<String> prompts = new ArrayList<>();
        Registry reg = new Registry()
                .action("load", c -> j("{'name':'Ann'}"))
                .action("check", c -> null)
                .model(c -> {
                    prompts.add(c.user());
                    return "```json\n{'label':'refund'}\n```".replace('\'', '"');
                });
        var r = FlowEngine.simple(reg).run(flow(TASK_FLOW), j("{'msg':'money back'}"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertEquals("refund", r.result().path("label").asText());
        assertEquals("Customer Ann says money back", prompts.get(0));
    }

    @Test
    void wrongShapeIsRejectedThenFailsAfterRetries() {
        AtomicInteger calls = new AtomicInteger();
        Registry reg = new Registry().action("load", c -> j("{'name':'Ann'}")).action("check", c -> null)
                .model(c -> {
                    calls.incrementAndGet();
                    return "{\"label\": 5}";
                });
        var r = FlowEngine.simple(reg).run(flow(TASK_FLOW), j("{'msg':'x'}"), "u");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertEquals("SHAPE", r.error().path("kind").asText());
        assertEquals(2, calls.get()); // 1 + retries(1)
    }

    @Test
    void afterActionCanRejectForRetry() {
        AtomicInteger n = new AtomicInteger();
        Registry reg = new Registry().action("load", c -> j("{'name':'Ann'}"))
                .action("check", c -> {
                    if (n.incrementAndGet() < 2) throw new IllegalStateException("wrong amount");
                    return null;
                })
                .model(c -> "{\"label\":\"ok\"}");
        var r = FlowEngine.simple(reg).run(flow(TASK_FLOW), j("{'msg':'x'}"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertEquals(2, n.get());
    }

    // ---- phase 2: straight line, instead-of action, chaining

    @Test
    void straightLineChainsResults() {
        Registry reg = new Registry()
                .action("double", c -> j("{'v':" + c.context().resolve("input.n").asInt() * 2 + "}"))
                .action("inc", c -> j("{'v':" + (c.context().resolve("steps.s1.v").asInt() + 1) + "}"));
        var f = flow("""
                {'name':'line','start':'s1','steps':{
                  's1':{'type':'action','action':'double','next':'s2'},
                  's2':{'type':'action','action':'inc','next':'end'},
                  'end':{'type':'finish','result':{'v':'{{steps.s2.v}}'}}}}""");
        var r = FlowEngine.simple(reg).run(f, j("{'n':20}"), "u");
        assertEquals("41", r.result().path("v").asText());
        assertEquals(List.of(":start t", "s1:action", "s2:action", "end:finish", ":completed").size(),
                FlowEngine.lines(r).size() - 0);
    }

    @Test
    void saveChecksFindProblems() {
        var f = flow("""
                {'name':'bad','start':'x','limits':{'retries':-1},'steps':{
                  's1':{'type':'action','action':'nope','next':'ghost'},
                  's2':{'type':'decide','by':'java','chooser':'c','outcomes':{'a':'ghost'}}}}""");
        List<String> p = FlowValidator.check(f, new Registry());
        assertTrue(p.stream().anyMatch(s -> s.contains("start step 'x'")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("retries")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("'nope' is not registered")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("next 'ghost'")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("outcome 'a'")), p.toString());
        assertThrows(FlowException.class, () -> FlowEngine.simple(new Registry()).run(f, j("{}"), "u"));
    }

    // ---- phase 3: choosing

    private static final String BRANCH = """
            {'name':'b','start':'d','steps':{
              'd':{'type':'decide','by':'java','chooser':'rule','question':'auto?',
                   'outcomes':{'approve':'yes','reject':'no'},'default':'no'},
              'yes':{'type':'finish','result':{'r':'approved'}},
              'no':{'type':'finish','result':{'r':'rejected'}}}}""";

    @Test
    void javaChooserBranches() {
        Registry reg = new Registry().chooser("rule", c -> c.context().resolve("input.amount").asInt() < 100
                ? "approve" : "reject");
        var e = FlowEngine.simple(reg);
        assertEquals("approved", e.run(flow(BRANCH), j("{'amount':5}"), "u").result().path("r").asText());
        assertEquals("rejected", e.run(flow(BRANCH), j("{'amount':500}"), "u").result().path("r").asText());
    }

    @Test
    void invalidOutcomeUsesDefaultOrFails() {
        Registry reg = new Registry().chooser("rule", c -> "banana");
        var r = FlowEngine.simple(reg).run(flow(BRANCH), j("{}"), "u");
        assertEquals("rejected", r.result().path("r").asText());
        var noDefault = flow(BRANCH.replace(",'default':'no'", ""));
        var r2 = FlowEngine.simple(reg).run(noDefault, j("{}"), "u");
        assertEquals(FlowEngine.Status.FAILED, r2.status());
        assertEquals("CHOOSER", r2.error().path("kind").asText());
    }

    @Test
    void modelChoiceIsClosedAndReplayReusesDecisions() {
        AtomicInteger asked = new AtomicInteger();
        Registry reg = new Registry().model(c -> {
            asked.incrementAndGet();
            return " approve ";
        });
        var f = flow(BRANCH.replace("'by':'java','chooser':'rule'", "'by':'llm','agent':'ag'"));
        var e = FlowEngine.simple(reg);
        var first = e.run(f, j("{}"), "u");
        assertEquals("approved", first.result().path("r").asText());
        assertEquals("llm", first.context().decisions().get(0).decidedBy());
        var replay = e.run(f, j("{}"), "u", first.decisions());
        assertEquals("approved", replay.result().path("r").asText());
        assertEquals(1, asked.get(), "replay must not ask the model again");
        assertEquals("recorded", replay.context().decisions().get(0).decidedBy());
    }

    // ---- phase 4: errors

    private static final String ERR = """
            {'name':'e','start':'s','limits':{'retries':2},'onError':{'start':'h','retryLimit':1,'steps':{
                'h':{'type':'decide','by':'java','chooser':'hc','outcomes':{'recover':'rec','again':'ret','fail':'bad'}},
                'rec':{'type':'finish','outcome':'recovered','result':{'why':'{{error.kind}}'}},
                'ret':{'type':'finish','outcome':'retry','retryStep':'s'},
                'bad':{'type':'finish','outcome':'rethrow'}}},
             'steps':{'s':{'type':'action','action':'boom','next':'end'},
                      'end':{'type':'finish','result':{'ok':'yes'}}}}""";

    @Test
    void errorIsRetriedThenHandledAndRecovered() {
        AtomicInteger booms = new AtomicInteger();
        Registry reg = new Registry().action("boom", c -> {
            booms.incrementAndGet();
            throw new Exception("down");
        }).chooser("hc", c -> "recover");
        var r = FlowEngine.simple(reg).run(flow(ERR), j("{}"), "u");
        assertEquals(3, booms.get()); // 1 + 2 retries
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertTrue(r.result().path("recovered").asBoolean());
        assertEquals("ACTION", r.result().path("value").path("why").asText());
        var kinds = r.history().stream().map(FlowEngine.Event::kind).toList();
        assertTrue(kinds.containsAll(List.of("retry", "error", "handler")), kinds.toString());
    }

    @Test
    void handlerCanRetryFromStepWithLimitThenRethrow() {
        AtomicInteger booms = new AtomicInteger();
        Registry reg = new Registry().action("boom", c -> {
            if (booms.incrementAndGet() < 100) throw new Exception("down");
            return j("{}");
        }).chooser("hc", c -> "again");
        // retryLimit 1: second handler call exceeds it -> failed
        var r = FlowEngine.simple(reg).run(flow(ERR), j("{}"), "u");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertTrue(r.error().path("limit").asText().contains("handler retries"));
        assertEquals(6, booms.get());
        // enough budget: succeeds on the retried run
        booms.set(100 - 3); // first walk (3 attempts) fails, retried walk succeeds
        var ok = FlowEngine.simple(reg).run(flow(ERR.replace("'retryLimit':1", "'retryLimit':3")), j("{}"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, ok.status());
        assertEquals("yes", ok.result().path("ok").asText());
    }

    @Test
    void rethrowAndDefaultHandlerFailTheRun() {
        Registry reg = new Registry().action("boom", c -> {
            throw new Exception("down");
        }).chooser("hc", c -> "fail");
        assertEquals(FlowEngine.Status.FAILED, FlowEngine.simple(reg).run(flow(ERR), j("{}"), "u").status());
        var noHandler = flow("""
                {'name':'d','start':'s','steps':{'s':{'type':'action','action':'boom','next':'end','retries':0},
                 'end':{'type':'finish'}}}""");
        var r = FlowEngine.simple(reg).run(noHandler, j("{}"), "u");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertEquals("s", r.error().path("step").asText());
        assertEquals(1, r.error().path("attempts").asInt());
    }

    @Test
    void handlerFailureCarriesBothErrors() {
        Registry reg = new Registry().action("boom", c -> {
            throw new Exception("down");
        }).chooser("hc", c -> {
            throw new IllegalStateException("handler broke");
        });
        var r = FlowEngine.simple(reg).run(flow(ERR), j("{}"), "u");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertEquals("CHOOSER", r.error().path("kind").asText());
        assertEquals("ACTION", r.error().path("original").path("kind").asText());
    }

    @Test
    void snapshotIsIndependentOfLaterEdits() {
        var f = flow(BRANCH);
        var snap = f.snapshot();
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.doc()).put("name", "changed");
        assertEquals("b", snap.name());
    }
}

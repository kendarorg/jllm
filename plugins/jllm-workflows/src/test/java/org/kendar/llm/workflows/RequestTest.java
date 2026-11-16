package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestTest {
    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    private static final String CHILD = """
            {'name':'kid','scope':'global','start':'a','steps':{
             'a':{'type':'ask','kind':'text','text':'Info?','next':'e',
                  'accepts':{'address':{'action':'update','key':'addr','kind':'text'}}},
             'e':{'type':'finish','result':{'v':'{{steps.a}} {{requests.addr}}'}}}}""";
    private static final String PARENT = """
            {'name':'par','scope':'global','start':'c','helper':'faq',
             'accepts':{'cancel':{'action':'cancel'},'back':{'action':'redirect','to':'again'}},
             'steps':{'c':{'type':'call','flow':'kid','scope':'global','next':'e'},
                      'again':{'type':'ask','kind':'text','text':'Addr?','next':'e'},
                      'e':{'type':'finish','result':{'ok':'yes'}}}}""";

    private FlowEngine engine() {
        var lib = new FlowLibrary.Memory().global(flow(CHILD)).global(flow(PARENT));
        return new FlowEngine(new Registry().action("faq", c -> j("{'answer':'42'}")), new RunStore.Memory(), lib);
    }

    @Test
    void parentCancelsEverythingBelow() {
        var e = engine();
        var r = e.run(flow(PARENT), j("{}"), "u");
        var o = e.request(r.runId(), "cancel", j("'x'"), "cust");
        assertEquals(r.runId(), o.handledBy());
        for (RunRecord rr : e.store().list("u")) assertEquals(RunRecord.CANCELLED, rr.status);
    }

    @Test
    void updateAllowedAtChildStageHelperAnswersAndRedirectJumps() {
        var e = engine();
        var r = e.run(flow(PARENT), j("{}"), "u");
        var o = e.request(r.runId(), "address", j("'Main St'"), "cust");
        assertNotEquals(r.runId(), o.handledBy(), "innermost run takes it");
        assertEquals(RunRecord.WAITING, e.store().load(r.runId()).get().status);
        var h = e.request(r.runId(), "faq-question", j("'?'"), "cust");
        assertEquals("helper", h.action());
        assertEquals("42", h.answer().path("answer").asText());
        var o2 = e.request(r.runId(), "back", j("{}"), "cust");
        assertEquals(FlowEngine.Status.WAITING, o2.run().status());
        var fin = e.resume(r.runId(), j("'ok'"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, fin.status());
    }

    @Test
    void updateIsVisibleToLaterSteps() {
        var e = engine();
        var r = e.run(flow(PARENT), j("{}"), "u");
        e.request(r.runId(), "address", j("'Main St'"), "cust");
        var fin = e.resume(r.runId(), j("'hi'"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, fin.status());
        assertEquals("hi Main St", e.store().list("u").stream().filter(x -> x.parentRunId != null)
                .findFirst().get().result.path("v").asText());
    }

    @Test
    void unacceptedRequestWithoutHelperIsRefusedAndStageStays() {
        var lib = new FlowLibrary.Memory().global(flow(CHILD));
        var e = new FlowEngine(new Registry(), new RunStore.Memory(), lib);
        var r = e.run(flow(CHILD), j("{}"), "u");
        assertThrows(FlowException.class, () -> e.request(r.runId(), "cancel", j("'x'"), "cust"));
        assertEquals(RunRecord.WAITING, e.store().load(r.runId()).get().status);
    }
}

package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NestedFlowTest {

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    private static final String REFUND = """
            {'name':'refund','scope':'global','start':'op','steps':{
              'op':{'type':'decide','by':'user','question':'Approve?','who':'operator',
                    'outcomes':{'approve':'pay','reject':'no'}},
              'pay':{'type':'action','action':'issue','next':'end'},
              'no':{'type':'finish','result':{'amount':'0'}},
              'end':{'type':'finish','result':{'amount':'{{input.amount}}','order':'{{input.order}}'}}}}""";

    private static final String SHIPPING = """
            {'name':'shipping','scope':'global','start':'addr','steps':{
              'addr':{'type':'ask','kind':'text','text':'Confirm address for {{input.order}}','next':'end'},
              'end':{'type':'finish','result':{'tracking':'T-{{steps.addr}}'}}}}""";

    private static final String COMPLAINT = """
            {'name':'complaint','scope':'global','start':'rule','steps':{
              'rule':{'type':'decide','by':'java','chooser':'compensation','question':'Compensation due?',
                      'outcomes':{'refund':'ref','replacement':'ship','apology':'end'}},
              'ref':{'type':'call','flow':'refund','scope':'global','input':{'order':'{{input.order}}','amount':'25'},'next':'store'},
              'ship':{'type':'call','flow':'shipping','scope':'global','input':{'order':'{{input.order}}'},'next':'store'},
              'store':{'type':'action','action':'save','next':'end'},
              'end':{'type':'finish','result':{'done':'yes'}}},
             'onError':{'start':'h','steps':{'h':{'type':'finish','outcome':'recovered','result':{'why':'{{error.kind}}'}}}}}""";

    private Registry registry(AtomicInteger issued, String compensation) {
        return new Registry()
                .chooser("compensation", c -> compensation)
                .action("issue", c -> {
                    issued.incrementAndGet();
                    return j("{'ok':true}");
                })
                .action("save", c -> null);
    }

    private FlowLibrary library() {
        return new FlowLibrary.Memory().global(flow(REFUND)).global(flow(SHIPPING));
    }

    @Test
    void refundChildWaitsAndParentEndsAfterOperatorAnswers() {
        AtomicInteger issued = new AtomicInteger();
        var store = new RunStore.Memory();
        var e = new FlowEngine(registry(issued, "refund"), store, library());
        var r = e.run(flow(COMPLAINT), j("{'order':'A1'}"), "u");
        assertEquals(FlowEngine.Status.WAITING, r.status());
        assertEquals("operator", r.pending().who(), "the child's question is visible from the parent");
        assertEquals(2, store.list("u").size());
        RunRecord parent = store.load(r.runId()).get();
        RunRecord child = store.load(parent.pending.get("childRunId").asText()).get();
        assertEquals(r.runId(), child.parentRunId, "child is linked to the parent step");

        var done = e.resume(r.runId(), j("'approve'"), "op"); // answered through the parent
        assertEquals(FlowEngine.Status.COMPLETED, done.status());
        assertEquals("yes", done.result().path("done").asText());
        assertEquals("25", done.context().result("ref").path("amount").asText());
        assertEquals(1, issued.get());
    }

    @Test
    void childAnsweredDirectlyContinuesParent() {
        var store = new RunStore.Memory();
        var e = new FlowEngine(registry(new AtomicInteger(), "replacement"), store, library());
        var r = e.run(flow(COMPLAINT), j("{'order':'B2'}"), "u");
        String childId = store.load(r.runId()).get().pending.get("childRunId").asText();
        e.resume(childId, j("'12 Main St'"), "customer");
        assertEquals(RunRecord.COMPLETED, store.load(r.runId()).get().status);
        assertEquals("T-12 Main St", store.load(r.runId()).get().results.get("ship#1").path("tracking").asText());
    }

    @Test
    void childSeesOnlyWhatItWasGiven() {
        var reg = new Registry().action("peek", c -> j("{'has':" + c.context().input().has("secret") + "}"));
        var lib = new FlowLibrary.Memory().global(flow("""
                {'name':'kid','scope':'global','start':'p','steps':{'p':{'type':'action','action':'peek','next':'end'},
                 'end':{'type':'finish','result':{'has':'{{steps.p.has}}'}}}}"""));
        var parent = flow("""
                {'name':'dad','scope':'global','start':'c','steps':{
                 'c':{'type':'call','flow':'kid','scope':'global','input':{'x':'1'},'next':'end'},
                 'end':{'type':'finish','result':{'r':'{{steps.c.has}}'}}}}""");
        var r = new FlowEngine(reg, new RunStore.Memory(), lib).run(parent, j("{'secret':'s'}"), "u");
        assertEquals("false", r.result().path("r").asText());
    }

    @Test
    void childErrorReachesParentHandler() {
        var reg = new Registry().action("boom", c -> {
            throw new Exception("down");
        });
        var lib = new FlowLibrary.Memory().global(flow("""
                {'name':'bad','scope':'global','start':'b','limits':{'retries':0},
                 'steps':{'b':{'type':'action','action':'boom','next':'end'},'end':{'type':'finish'}}}"""));
        var parent = flow("""
                {'name':'p','scope':'global','start':'c','steps':{
                 'c':{'type':'call','flow':'bad','scope':'global','next':'end'},'end':{'type':'finish'}},
                 'onError':{'start':'h','steps':{'h':{'type':'finish','outcome':'recovered','result':{'why':'{{error.kind}}','msg':'{{error.message}}'}}}}}""");
        var r = new FlowEngine(reg, new RunStore.Memory(), lib).run(parent, j("{}"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertEquals("CHILD", r.result().path("value").path("why").asText());
        assertTrue(r.result().path("value").path("msg").asText().contains("down"));
    }

    @Test
    void cancellingParentCancelsChildren() {
        var store = new RunStore.Memory();
        var e = new FlowEngine(registry(new AtomicInteger(), "refund"), store, library());
        var r = e.run(flow(COMPLAINT), j("{'order':'C3'}"), "u");
        e.cancel(r.runId());
        for (RunRecord rr : store.list("u")) assertEquals(RunRecord.CANCELLED, rr.status);
    }

    @Test
    void saveTimeRejectsLoopsSelfCallsAndPersonalFromGlobal() {
        var a = flow("""
                {'name':'a','scope':'global','start':'c','steps':{'c':{'type':'call','flow':'b','scope':'global','next':'e'},'e':{'type':'finish'}}}""");
        var b = flow("""
                {'name':'b','scope':'global','start':'c','steps':{'c':{'type':'call','flow':'a','scope':'global','next':'e'},'e':{'type':'finish'}}}""");
        var lib = new FlowLibrary.Memory().global(a).global(b);
        assertTrue(FlowLibrary.checkChildren(a, lib, "u").stream().anyMatch(s -> s.contains("call itself")));
        var self = flow("""
                {'name':'s','start':'c','steps':{'c':{'type':'call','flow':'s','next':'e'},'e':{'type':'finish'}}}""");
        assertTrue(FlowValidator.check(self, null).stream().anyMatch(s -> s.contains("may not call itself")));
        var g = flow("""
                {'name':'g','scope':'global','start':'c','steps':{'c':{'type':'call','flow':'mine','scope':'personal','next':'e'},'e':{'type':'finish'}}}""");
        var mine = flow("{'name':'mine','start':'e','steps':{'e':{'type':'finish'}}}");
        var lib2 = new FlowLibrary.Memory().personal("u", mine);
        assertTrue(FlowLibrary.checkChildren(g, lib2, "u").stream().anyMatch(s -> s.contains("cannot call the personal")));
    }

    @Test
    void depthLimitIsDecidedByStartedFlowAndChildCannotRaiseIt() {
        String pat = """
                {'name':'%s','scope':'global',%s'start':'c','steps':{'c':{'type':'call','flow':'%s','scope':'global','next':'e'},'e':{'type':'finish'}}}""";
        var leaf = flow("{'name':'l3','scope':'global','start':'e','steps':{'e':{'type':'finish'}}}");
        var l2 = flow(pat.formatted("l2", "'limits':{'depth':9},", "l3"));
        var l1 = flow(pat.formatted("l1", "", "l2"));
        var top = flow(pat.formatted("top", "'limits':{'depth':3},", "l1"));
        var lib = new FlowLibrary.Memory().global(leaf).global(l2).global(l1).global(top);
        var e = new FlowEngine(new Registry(), new RunStore.Memory(), lib);
        // top -> l1 -> l2 -> l3 is 4 levels; top allows 3. Refused when saved.
        var ex = assertThrows(FlowException.class, () -> e.run(top, j("{}"), "u"));
        assertTrue(ex.getMessage().contains("depth limit is 3"), ex.getMessage());
        // allowed with 4: runs; l2 asking for 9 does not raise the limit but is within it
        var ok = flow(pat.formatted("top", "'limits':{'depth':4},", "l1"));
        assertEquals(FlowEngine.Status.COMPLETED, new FlowEngine(new Registry(), new RunStore.Memory(),
                new FlowLibrary.Memory().global(leaf).global(l2).global(l1).global(ok)).run(ok, j("{}"), "u").status());
    }

    @Test
    void runtimeChosenChildMustBeInAllowedSet() {
        var kid = flow("{'name':'k1','scope':'global','start':'e','steps':{'e':{'type':'finish','result':{'k':'1'}}}}");
        var other = flow("{'name':'k2','scope':'global','start':'e','steps':{'e':{'type':'finish'}}}");
        var lib = new FlowLibrary.Memory().global(kid).global(other);
        var parent = flow("""
                {'name':'p','scope':'global','start':'c','steps':{
                 'c':{'type':'call','chooser':'which','allowed':['k1'],'scope':'global','next':'end'},
                 'end':{'type':'finish','result':{'k':'{{steps.c.k}}'}}},
                 'onError':{'start':'h','steps':{'h':{'type':'finish','outcome':'rethrow'}}}}""");
        var good = new FlowEngine(new Registry().chooser("which", c -> "k1"), new RunStore.Memory(), lib);
        assertEquals("1", good.run(parent, j("{}"), "u").result().path("k").asText());
        var bad = new FlowEngine(new Registry().chooser("which", c -> "k2"), new RunStore.Memory(), lib);
        var r = bad.run(parent, j("{}"), "u");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertEquals("CHOOSER", r.error().path("kind").asText());
    }
}

package org.kendar.llm.workflows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskApprovalTest {
    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    private static final String TASK = "'tasks':{'pay':{'agent':'payer','prompt':'pay','answer':'text'}},"
            + "'steps':{'a':{'type':'task','task':'pay','next':'end'},'end':{'type':'finish','result':{'r':'{{steps.a}}'}}}}";

    /** A model whose agent needs 'pay' approved before it answers. */
    private static Registry payer(List<Set<String>> seen, List<String> titles) {
        return new Registry().model(c -> {
            seen.add(c.approved());
            titles.add(c.title());
            if (!c.approved().contains("pay")) throw new Registry.ApprovalNeededException("needs pay", List.of("pay"));
            return "paid";
        });
    }

    @Test
    void attendedFlowAsksTheUserThenRunsTheTaskWithTheToolAllowed() {
        List<Set<String>> seen = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        var e = FlowEngine.simple(payer(seen, titles));
        var r = e.run(flow("{'name':'p','start':'a'," + TASK), j("{}"), "ann");
        assertEquals(FlowEngine.Status.WAITING, r.status());
        assertEquals("Allow the tool 'pay'?", r.pending().title());
        r = e.resume(r.runId(), j("'approve'"), "ann");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertEquals("paid", r.result().path("r").asText());
        assertEquals(Set.of("pay"), seen.get(seen.size() - 1));
        assertTrue(titles.get(0).startsWith("flow: p "), titles.get(0));
    }

    @Test
    void refusingTheToolFailsTheStepWithAPermissionError() {
        var e = FlowEngine.simple(payer(new ArrayList<>(), new ArrayList<>()));
        var r = e.run(flow("{'name':'p','start':'a'," + TASK), j("{}"), "ann");
        r = e.resume(r.runId(), j("'deny'"), "ann");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertEquals("PERMISSION", r.error().path("kind").asText());
    }

    @Test
    void unattendedFlowGetsADeniedResultAndGoesOn() {
        List<Boolean> flags = new ArrayList<>();
        var e = FlowEngine.simple(new Registry().model(c -> {
            flags.add(c.unattended());
            if (!c.approved().contains("pay")) { // the host denies the call, the model sees the denial and answers
                c.onDenied().accept("pay");
                return "could not pay: denied: unattended run, no one can approve pay";
            }
            return "paid";
        }));
        var r = e.run(flow("{'name':'p','unattended':true,'start':'a'," + TASK), j("{}"), "ann");
        assertEquals(FlowEngine.Status.COMPLETED, r.status(), "no question, the flow is not failed");
        assertTrue(r.result().path("r").asText().startsWith("could not pay"));
        assertEquals(List.of(true), flags);
        var denied = r.history().stream().filter(ev -> ev.kind().equals("denied")).toList();
        assertEquals(1, denied.size(), "counted in the journal");
        assertEquals("pay", denied.get(0).data().path("tool").asText());
        assertTrue(denied.get(0).detail().contains("no one can approve pay"));
    }

    @Test
    void anAttendedFlowIsNotMarkedUnattended() {
        List<Boolean> flags = new ArrayList<>();
        var e = FlowEngine.simple(new Registry().model(c -> {
            flags.add(c.unattended());
            return "ok";
        }));
        e.run(flow("{'name':'p','start':'a'," + TASK), j("{}"), "ann");
        assertEquals(List.of(false), flags);
    }

    private static com.fasterxml.jackson.databind.JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }
}

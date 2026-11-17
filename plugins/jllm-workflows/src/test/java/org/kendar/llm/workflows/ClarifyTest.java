package org.kendar.llm.workflows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClarifyTest {
    private static FlowDef flow(String extra) {
        return FlowDef.parse(("{'name':'c','start':'a'" + extra + ",'tasks':{'t':{'agent':'x','prompt':'work','answer':'text'}},"
                + "'steps':{'a':{'type':'task','task':'t','next':'end'},'end':{'type':'finish','result':{'r':'{{steps.a}}'}}}}")
                .replace('\'', '"'));
    }

    private static Registry model(List<String> prompts, String firstReply, String verdict, List<String> verdicts) {
        return new Registry().model(c -> {
            prompts.add(c.user());
            return prompts.size() == 1 ? firstReply : "done";
        }).classifier((p, s, u) -> {
            verdicts.add(u);
            return verdict;
        });
    }

    @Test
    void aReplyThatIsNotAQuestionIsTakenWithoutTheClassifier() {
        var verdicts = new ArrayList<String>();
        var e = FlowEngine.simple(model(new ArrayList<>(), "All finished.", "ask", verdicts));
        var r = e.run(flow(""), Json.parse("{}"), "ann");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertTrue(verdicts.isEmpty());
    }

    @Test
    void proceedRunsTheTaskAgainWithoutAsking() {
        var prompts = new ArrayList<String>();
        var e = FlowEngine.simple(model(prompts, "Should I use tabs?", "proceed", new ArrayList<>()));
        var r = e.run(flow(""), Json.parse("{}"), "ann");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertEquals("done", r.result().path("r").asText());
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("Do not ask questions"));
    }

    @Test
    void askPausesWithAFreeTextQuestionAndContinuesWithTheAnswer() {
        var prompts = new ArrayList<String>();
        var e = FlowEngine.simple(model(prompts, "Which database should I use?", "ask", new ArrayList<>()));
        var r = e.run(flow(""), Json.parse("{}"), "ann");
        assertEquals(FlowEngine.Status.WAITING, r.status());
        assertEquals("TEXT", r.pending().kind().name());
        assertTrue(r.pending().text().contains("Which database"));
        r = e.resume(r.runId(), Json.parse("\"postgres\""), "ann");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
        assertEquals(2, prompts.size(), "the first reply is journaled; the model is not called again on resume");
        assertTrue(prompts.get(1).contains("postgres"));
    }

    @Test
    void anUnattendedFlowProceedsInsteadOfAsking() {
        var e = FlowEngine.simple(model(new ArrayList<>(), "Which database should I use?", "ask", new ArrayList<>()));
        var r = e.run(flow(",'unattended':true"), Json.parse("{}"), "ann");
        assertEquals(FlowEngine.Status.COMPLETED, r.status());
    }

    @Test
    void theClassifierAsksForTheUserOfTheRun() {
        var users = new ArrayList<String>();
        var reg = new Registry().model(c -> "Which database should I use?").classifier((p, sys, u) -> {
            users.add(p);
            return "none";
        });
        FlowEngine.simple(reg).run(flow(""), Json.parse("{}"), "ann");
        assertEquals(List.of("ann"), users);
    }

    @Test
    void aMissingLoginIsNotSwallowedAsANoneVerdict() {
        var reg = new Registry().model(c -> "Which database should I use?").classifier((p, sys, u) -> {
            throw new org.kendar.llm.harness.auth.LoginRequiredException("provider:p");
        });
        Throwable failure = null;
        FlowEngine.RunResult r = null;
        try {
            r = FlowEngine.simple(reg).run(flow(""), Json.parse("{}"), "ann");
        } catch (RuntimeException e) {
            failure = e;
        }
        String shown = failure != null ? String.valueOf(failure.getMessage()) + failure.getCause()
                : r.status() + " " + r.error();
        assertTrue(shown.contains("provider:p"), shown);
    }
}

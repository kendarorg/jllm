package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Step parameters ({@code with}), the built-in {@code gate} chooser and the save-time checks of both built-ins. */
class GateFlowTest {

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private static FlowDef flow(String s) {
        return FlowDef.parse(s.replace('\'', '"'));
    }

    private static Registry builtins() {
        Registry r = new Registry();
        new BuiltinActions().register(r);
        return r;
    }

    private static final String REVIEW = """
            {'name':'rv','start':'review','steps':{
              'review':{'type':'action','action':'review','next':'gate'},
              'gate':{'type':'decide','by':'java','chooser':'gate','question':'Approved?',
                      'with':{'from':'steps.review','field':'approved','type':'boolean',
                              'map':{'true':'approved','false':'changes'},'fallback':'changes'},
                      'outcomes':{'approved':'ok','changes':'rework'},'default':'rework'},
              'ok':{'type':'finish','result':{'r':'test'}},
              'rework':{'type':'finish','result':{'r':'rework'}}}}""";

    private static FlowEngine.RunResult review(String answer) {
        Registry reg = builtins().action("review", c -> j(answer));
        return FlowEngine.simple(reg).run(flow(REVIEW), j("{}"), "u");
    }

    private static JsonNode decision(FlowEngine.RunResult r, String step) {
        return r.history().stream().filter(e -> e.step().equals(step) && e.kind().equals("decision"))
                .findFirst().orElseThrow().data();
    }

    @Test
    void gateRoutesOnTheStructuredField() {
        var ok = review("{'approved':true,'issues':[]}");
        assertEquals(FlowEngine.Status.COMPLETED, ok.status(), String.valueOf(ok.error()));
        assertEquals("test", ok.result().path("r").asText());
        var d = decision(ok, "gate");
        assertEquals("approved", d.path("outcome").asText());
        assertTrue(d.path("detail").path("value").booleanValue());
        assertFalse(d.path("detail").path("fellBack").booleanValue());
        assertEquals("steps.review", d.path("detail").path("gate").asText());
        assertFalse(d.path("detail").path("reason").asText().isBlank());
        assertEquals("approved", ok.context().decisions().get(0).outcome());
        assertTrue(ok.context().decisions().get(0).inputs().has("reason"));
    }

    @Test
    void proseSayingApprovedWithFalseFieldGoesToRework() {
        var r = review("{'approved':false,'issues':['no tests'],'summary':'APPROVED'}");
        assertEquals("rework", r.result().path("r").asText());
        assertEquals("changes", decision(r, "gate").path("outcome").asText());
        assertFalse(decision(r, "gate").path("detail").path("fellBack").booleanValue());
    }

    @Test
    void invalidAnswerFallsBackAndTheReasonIsJournaled() {
        var r = review("{'approved':'yes'}");
        assertEquals("rework", r.result().path("r").asText());
        JsonNode d = decision(r, "gate").path("detail");
        assertTrue(d.path("fellBack").booleanValue());
        assertEquals("yes", d.path("value").asText());
        assertTrue(d.path("reason").asText().contains("fallback"), d.toString());
        var missing = review("{'issues':[]}");
        assertTrue(decision(missing, "gate").path("detail").path("fellBack").booleanValue());
        assertEquals("rework", missing.result().path("r").asText());
    }

    @Test
    void gateDetailSurvivesAResume() {
        String f = REVIEW.replace("'ok':{'type':'finish','result':{'r':'test'}}",
                "'ok':{'type':'ask','kind':'text','text':'Go?','next':'end'},'end':{'type':'finish','result':{'r':'test'}}");
        Registry reg = builtins().action("review", c -> j("{'approved':true}"));
        var e = FlowEngine.simple(reg);
        var w = e.run(flow(f), j("{}"), "u");
        assertEquals(FlowEngine.Status.WAITING, w.status());
        var done = e.resume(w.runId(), j("'yes'"), "u");
        assertEquals(FlowEngine.Status.COMPLETED, done.status());
        assertEquals("approved", decision(done, "gate").path("detail").path("response").asText());
        RunRecord stored = e.store().load(w.runId()).orElseThrow();
        assertEquals(1, stored.decisionDetails.size());
        RunRecord reread = RunRecord.fromJson(stored.toJson());
        assertEquals(stored.decisionDetails, reread.decisionDetails);
    }

    @Test
    void gateReadsInput() {
        String f = """
                {'name':'in','start':'g','steps':{
                  'g':{'type':'decide','by':'java','chooser':'gate',
                       'with':{'from':'input','field':'level','type':'string','map':{'top':'high','*':'low'},'fallback':'low'},
                       'outcomes':{'high':'h','low':'l'}},
                  'h':{'type':'finish','result':{'r':'high'}},
                  'l':{'type':'finish','result':{'r':'low'}}}}""";
        var e = FlowEngine.simple(builtins());
        assertEquals("high", e.run(flow(f), j("{'level':'top'}"), "u").result().path("r").asText());
        assertEquals("low", e.run(flow(f), j("{'level':'mid'}"), "u").result().path("r").asText());
    }

    @Test
    void actionsAndChoosersReceiveRenderedParams() {
        List<JsonNode> seen = new ArrayList<>();
        Registry reg = new Registry().action("p", c -> {
            seen.add(c.params());
            return null;
        }).chooser("c", c -> c.params().path("pick").asText());
        var f = flow("""
                {'name':'p','start':'a','steps':{
                  'a':{'type':'action','action':'p','with':{'msg':'hi {{input.who}}','n':3,'deep':{'x':'{{input.who}}'}},'next':'b'},
                  'b':{'type':'action','action':'p','next':'d'},
                  'd':{'type':'decide','by':'java','chooser':'c','with':{'pick':'{{input.go}}'},'outcomes':{'left':'l','right':'r'}},
                  'l':{'type':'finish','result':{'r':'l'}},
                  'r':{'type':'finish','result':{'r':'r'}}}}""");
        var r = FlowEngine.simple(reg).run(f, j("{'who':'Ann','go':'right'}"), "u");
        assertEquals("r", r.result().path("r").asText());
        assertEquals("hi Ann", seen.get(0).path("msg").asText());
        assertEquals(3, seen.get(0).path("n").asInt());
        assertEquals("Ann", seen.get(0).path("deep").path("x").asText());
        assertTrue(seen.get(1).isObject() && seen.get(1).isEmpty(), "no 'with' gives an empty object");
    }

    @Test
    void missingTemplateValueInParamsFailsTheStep() {
        Registry reg = new Registry().action("p", c -> null);
        var f = flow("""
                {'name':'p','start':'a','steps':{
                  'a':{'type':'action','action':'p','with':{'msg':'{{input.none}}'},'next':'e'},
                  'e':{'type':'finish'}}}""");
        var r = FlowEngine.simple(reg).run(f, j("{}"), "u");
        assertEquals(FlowEngine.Status.FAILED, r.status());
        assertTrue(r.error().path("message").asText().contains("input.none"), r.error().toString());
    }

    // ---- save-time checks

    private static List<String> check(String f) {
        return FlowValidator.check(flow(f), builtins().action("review", c -> null));
    }

    private static void hasProblem(List<String> p, String text) {
        assertTrue(p.stream().anyMatch(s -> s.contains(text)), "expected '" + text + "' in " + p);
    }

    @Test
    void validGateFlowPasses() {
        assertEquals(List.of(), check(REVIEW));
    }

    @Test
    void withMustBeAnObject() {
        hasProblem(check(REVIEW.replace("'next':'gate'}", "'next':'gate','with':'x'}")), "'with' must be an object");
    }

    @Test
    void gateResponsesMustBeDeclaredOutcomes() {
        hasProblem(check(REVIEW.replace("'fallback':'changes'", "'fallback':'broken'")),
                "gate response 'broken' is not a declared outcome");
        hasProblem(check(REVIEW.replace("'false':'changes'", "'false':'later'")), "response 'later'");
    }

    @Test
    void gateFromMustPointToInputOrAStep() {
        hasProblem(check(REVIEW.replace("'from':'steps.review'", "'from':'steps.ghost'")), "gate 'from'");
        hasProblem(check(REVIEW.replace("'from':'steps.review'", "'from':'error.message'")), "gate 'from'");
        hasProblem(check(REVIEW.replace("'from':'steps.review',", "")), "gate 'from'");
        assertEquals(List.of(), check(REVIEW.replace("'from':'steps.review'", "'from':'input.review'")));
    }

    @Test
    void badGateConfigurationFailsAtSave() {
        hasProblem(check(REVIEW.replace("'type':'boolean'", "'type':'colour'")), "gate: type must be");
        hasProblem(check(REVIEW.replace("'true':'approved'", "'yes':'approved'")), "gate: boolean map key");
        hasProblem(check(REVIEW.replace(",'fallback':'changes'", "")), "gate: fallback is required");
        hasProblem(check(REVIEW.replace("'fallback':'changes'", "'fallback':'changes','allowed':['approved']")),
                "gate: response 'changes' is not allowed");
        String noWith = REVIEW.replaceAll("(?s)'with':\\{'from'.*?'fallback':'changes'},", "");
        assertNotEquals(REVIEW, noWith);
        hasProblem(check(noWith), "gate needs a 'with' object");
        Workflows w = new Workflows(builtins().action("review", c -> null), new RunStore.Memory(),
                new FlowStore.Memory(), u -> java.util.Set.of(), "admin");
        assertThrows(FlowException.class, () -> w.save("u", FlowLibrary.PERSONAL,
                flow(REVIEW.replace("'type':'boolean'", "'type':'colour'"))));
    }

    private static final String RUN = """
            {'name':'rc','start':'t','steps':{
              't':{'type':'action','action':'run-command','with':{'command':'echo hi','timeoutSeconds':5},'next':'e'},
              'e':{'type':'finish'}}}""";

    @Test
    void runCommandChecks() {
        assertEquals(List.of(), check(RUN));
        hasProblem(check(RUN.replace("'command':'echo hi',", "")), "run-command needs a 'command'");
        hasProblem(check(RUN.replace("'command':'echo hi'", "'command':'  '")), "run-command needs a 'command'");
        hasProblem(check(RUN.replace("'timeoutSeconds':5", "'timeoutSeconds':0")), "timeoutSeconds must be a positive");
        hasProblem(check(RUN.replace("'timeoutSeconds':5", "'timeoutSeconds':-2")), "timeoutSeconds must be a positive");
        hasProblem(check(RUN.replace("'timeoutSeconds':5", "'timeoutSeconds':'5'")), "timeoutSeconds must be a positive");
        hasProblem(check(RUN.replace("'timeoutSeconds':5", "'tailLines':-1")), "tailLines");
    }

    @Test
    void builtinsAreFoundByServiceLoader() {
        Registry r = ActionProvider.load(getClass().getClassLoader());
        assertNotNull(r.chooser(BuiltinActions.GATE));
        assertNotNull(r.action(BuiltinActions.RUN_COMMAND));
    }
}

package org.kendar.llm.harness.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GateResultsHandlerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode j(String s) {
        try {
            return M.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static GateResultsHandler gate(String with) {
        return new GateResultsHandler(GateResultsHandler.Config.fromJson(j(with)));
    }

    private static final String REVIEW = "{'from':'steps.review','field':'approved','type':'boolean',"
            + "'map':{'true':'approved','false':'changes'},'fallback':'changes'}";

    @Test
    void booleanMapsBothValues() {
        var g = gate(REVIEW);
        var yes = g.decide(j("{'approved':true,'issues':[]}"));
        assertEquals("approved", yes.response());
        assertFalse(yes.fellBack());
        assertTrue(yes.value().booleanValue());
        assertTrue(yes.reason().contains("approved"), yes.reason());
        var no = g.decide(j("{'approved':false,'issues':['x']}"));
        assertEquals("changes", no.response());
        assertFalse(no.fellBack());
    }

    @Test
    void booleanNeverReadsProse() {
        var r = gate(REVIEW).decide(j("{'approved':'APPROVED, looks great'}"));
        assertEquals("changes", r.response());
        assertTrue(r.fellBack());
        assertEquals("APPROVED, looks great", r.value().asText());
        assertTrue(r.reason().contains("a string, not boolean"), r.reason());
    }

    @Test
    void missingFieldAndMissingResultFallBack() {
        var g = gate(REVIEW);
        var r = g.decide(j("{'issues':[]}"));
        assertTrue(r.fellBack());
        assertEquals("changes", r.response());
        assertTrue(r.value().isNull());
        assertTrue(r.reason().contains("missing"), r.reason());
        assertTrue(g.decide(j("null")).fellBack());
        assertTrue(g.decide((JsonNode) null).fellBack());
        assertTrue(g.decide(j("{'approved':null}")).fellBack());
    }

    @Test
    void stringExactMatchStarAndUnknown() {
        var g = gate("{'type':'string','field':'state','map':{'ok':'go','retry':'again'},'fallback':'stop'}");
        assertEquals("go", g.decide(j("{'state':'ok'}")).response());
        assertEquals("again", g.decide(j("{'state':'retry'}")).response());
        var unknown = g.decide(j("{'state':'OK'}"));
        assertEquals("stop", unknown.response());
        assertTrue(unknown.fellBack());
        assertTrue(unknown.reason().contains("not mapped"), unknown.reason());
        assertTrue(g.decide(j("{'state':5}")).fellBack());

        var star = gate("{'type':'string','field':'state','map':{'ok':'go','*':'other'},'fallback':'stop'}");
        var any = star.decide(j("{'state':'whatever'}"));
        assertEquals("other", any.response());
        assertFalse(any.fellBack());
        assertTrue(star.decide(j("{'state':true}")).fellBack(), "* only matches values of the right type");
    }

    @Test
    void wholeValueWhenNoField() {
        var g = gate("{'type':'string','map':{'yes':'y'},'fallback':'n'}");
        assertEquals("y", g.decide(j("'yes'")).response());
        assertEquals("n", g.decide(j("{'yes':1}")).response());
    }

    @Test
    void nestedField() {
        var g = gate("{'type':'boolean','field':'review.ok','map':{'true':'a','false':'b'},'fallback':'b'}");
        assertEquals("a", g.decide(j("{'review':{'ok':true}}")).response());
    }

    @Test
    void numberMatchesByValue() {
        var g = gate("{'type':'number','field':'score','map':{'1':'one','2.5':'twoandhalf','*':'other'},'fallback':'bad'}");
        assertEquals("one", g.decide(j("{'score':1}")).response());
        assertEquals("one", g.decide(j("{'score':1.0}")).response());
        assertEquals("twoandhalf", g.decide(j("{'score':2.50}")).response());
        assertEquals("other", g.decide(j("{'score':7}")).response());
        var wrong = g.decide(j("{'score':'1'}"));
        assertEquals("bad", wrong.response());
        assertTrue(wrong.fellBack());
    }

    @Test
    void exitCodeFromIntAndFromJson() {
        var g = gate("{'from':'steps.runtests','field':'exitCode','type':'exitCode','map':{'0':'passed','*':'failed'},'fallback':'failed'}");
        var ok = g.decide(0);
        assertEquals("passed", ok.response());
        assertFalse(ok.fellBack());
        assertEquals(0, ok.value().intValue());
        var ko = g.decide(2);
        assertEquals("failed", ko.response());
        assertFalse(ko.fellBack());
        assertTrue(ko.reason().contains("*"), ko.reason());
        assertEquals("passed", g.decide(j("{'exitCode':0,'tail':'ok'}")).response());
        assertEquals("failed", g.decide(j("{'exitCode':1}")).response());
        var frac = g.decide(j("{'exitCode':0.5}"));
        assertTrue(frac.fellBack());
        assertTrue(g.decide(j("{'exitCode':'0'}")).fellBack());
        assertTrue(g.decide(j("{'tail':'no code'}")).fellBack());
    }

    @Test
    void exitCodeWithoutStarFallsBackOnUnknownCode() {
        var g = gate("{'type':'exitCode','map':{'0':'passed','1':'failed'},'fallback':'broken'}");
        assertEquals("failed", g.decide(1).response());
        var r = g.decide(137);
        assertEquals("broken", r.response());
        assertTrue(r.fellBack());
    }

    @Test
    void responsesAreMapValuesAndFallback() {
        assertEquals(Set.of("approved", "changes"), gate(REVIEW).responses());
        assertEquals(Set.of("go", "stop"), gate("{'type':'string','map':{'a':'go','b':'go'},'fallback':'stop'}").responses());
    }

    @Test
    void allowedDefaultsAndChecks() {
        var c = GateResultsHandler.Config.fromJson(j(REVIEW));
        assertEquals(Set.of("approved", "changes"), c.allowed());
        var wide = GateResultsHandler.Config.fromJson(j("{'type':'boolean','map':{'true':'a'},'fallback':'b','allowed':['a','b','c']}"));
        assertEquals(Set.of("a", "b", "c"), wide.allowed());
        assertThrows(IllegalArgumentException.class, () -> GateResultsHandler.Config.fromJson(
                j("{'type':'boolean','map':{'true':'a','false':'x'},'fallback':'b','allowed':['a','b']}")));
        assertThrows(IllegalArgumentException.class, () -> GateResultsHandler.Config.fromJson(
                j("{'type':'boolean','map':{'true':'a'},'fallback':'z','allowed':['a','b']}")));
        assertThrows(IllegalArgumentException.class, () -> new GateResultsHandler.Config(null,
                GateResultsHandler.ValueType.STRING, Map.of("x", "a"), "b", Set.of("a")));
    }

    @Test
    void badConfigurationIsRejected() {
        String[] bad = {
                "{'map':{'true':'a'},'fallback':'b'}",                               // no type
                "{'type':'colour','map':{'true':'a'},'fallback':'b'}",               // unknown type
                "{'type':'boolean','map':{},'fallback':'b'}",                        // empty map
                "{'type':'boolean','fallback':'b'}",                                 // no map
                "{'type':'boolean','map':{'true':'a'}}",                             // no fallback
                "{'type':'boolean','map':{'true':'a'},'fallback':''}",               // blank fallback
                "{'type':'boolean','map':{'yes':'a'},'fallback':'b'}",               // not a boolean key
                "{'type':'number','map':{'one':'a'},'fallback':'b'}",                // not a number key
                "{'type':'exitCode','map':{'1.5':'a'},'fallback':'b'}",              // not an exit code
                "{'type':'boolean','map':{'true':1},'fallback':'b'}",                // response not text
                "{'type':'boolean','map':{'true':''},'fallback':'b'}",               // blank response
                "{'type':'boolean','map':{'true':'a'},'fallback':'b','allowed':'a'}",// allowed not a list
                "{'type':'boolean','field':3,'map':{'true':'a'},'fallback':'b'}",    // field not text
                "[1]"};
        for (String b : bad) {
            assertThrows(IllegalArgumentException.class, () -> GateResultsHandler.Config.fromJson(j(b)), b);
        }
        assertThrows(IllegalArgumentException.class, () -> new GateResultsHandler(null));
    }

    @Test
    void typeNamesParse() {
        assertEquals(GateResultsHandler.ValueType.EXIT_CODE, GateResultsHandler.ValueType.parse("exitCode"));
        assertEquals(GateResultsHandler.ValueType.EXIT_CODE, GateResultsHandler.ValueType.parse("EXIT_CODE"));
        assertEquals(GateResultsHandler.ValueType.BOOLEAN, GateResultsHandler.ValueType.parse("Boolean"));
    }
}

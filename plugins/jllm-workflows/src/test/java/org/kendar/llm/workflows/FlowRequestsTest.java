package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowRequestsTest {

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    @Test
    void textShapeTrimsAndNullIsEmpty() {
        assertEquals("hi", FlowRequests.AnswerShape.validate(j("'text'"), "  hi \n").asText());
        assertEquals("hi", FlowRequests.AnswerShape.validate(Json.M.missingNode(), " hi ").asText());
        assertEquals("", FlowRequests.AnswerShape.validate(j("'text'"), null).asText());
    }

    @Test
    void objectShapeAcceptsFencedJsonAndOptionalFields() {
        JsonNode n = FlowRequests.AnswerShape.validate(j("{'label':'string','score?':'number'}"),
                "```json\n{\"label\":\"x\"}\n```");
        assertEquals("x", n.get("label").asText());
    }

    @Test
    void objectShapeRejectsMissingWrongTypeAndNonObject() {
        JsonNode s = j("{'label':'string','n':'number','b':'boolean','a':'array','o':'object'}");
        FlowException e = assertThrows(FlowException.class, () -> FlowRequests.AnswerShape.validate(s, "{}"));
        assertEquals(FlowException.Kind.SHAPE, e.kind());
        assertTrue(e.getMessage().contains("label"));
        e = assertThrows(FlowException.class, () -> FlowRequests.AnswerShape.validate(s,
                "{\"label\":1,\"n\":1,\"b\":true,\"a\":[],\"o\":{}}"));
        assertTrue(e.getMessage().contains("must be a string"));
        e = assertThrows(FlowException.class, () -> FlowRequests.AnswerShape.validate(s, "[1]"));
        assertTrue(e.getMessage().contains("not a JSON object"));
        JsonNode ok = FlowRequests.AnswerShape.validate(s, "{\"label\":\"a\",\"n\":1,\"b\":true,\"a\":[],\"o\":{}}");
        assertTrue(ok.isObject());
    }

    @Test
    void buildQuestionAppliesDefaultsPerKind() {
        FlowContext ctx = new FlowContext("r", "u", Json.M.createObjectNode().put("who", "Ann"));
        Question single = FlowRequests.buildQuestion(ctx, "k", j("{'question':'Pick','text':'Hello {{input.who}}'}"),
                List.of(new Question.Option("a", "A")));
        assertEquals("k", single.key());
        assertEquals("Pick", single.title());
        assertEquals("Hello Ann", single.text());
        assertEquals(1, single.min());
        assertEquals(1, single.max());
        assertEquals("owner", single.who());
        assertTrue(single.required());
        assertNull(single.pattern());
        assertNull(single.facts());

        Question txt = FlowRequests.buildQuestion(ctx, "k2", j(
                "{'text':'Name?','kind':'text','who':'customer','pattern':'number','required':false,"
                        + "'facts':{'f':'{{input.who}}'}}"), List.of());
        assertEquals("Name?", txt.title());
        assertEquals(0, txt.min());
        assertFalse(txt.required());
        assertEquals("customer", txt.who());
        assertEquals("number", txt.pattern());
        assertEquals("Ann", txt.facts().get("f").asText());
    }

    @Test
    void storeAnswerValidatesAndRecordsByKey() {
        RunRecord rec = new RunRecord("r", "u", Json.M.createObjectNode(), Json.M.createObjectNode());
        rec.pending = Question.text("q1", "Name?").toJson();
        assertThrows(FlowException.class, () -> FlowRequests.storeAnswer(rec, Json.M.createArrayNode(), "bob"));
        assertFalse(rec.answers.has("q1"));
        FlowRequests.storeAnswer(rec, Json.M.getNodeFactory().textNode("Ann"), "bob");
        assertEquals("Ann", rec.answers.get("q1").get("value").asText());
        assertEquals("bob", rec.answers.get("q1").get("by").asText());
    }

    @Test
    void waitingRunRefusesUnknownAndNotWaiting() {
        RunStore store = new RunStore.Memory();
        FlowRequests fr = new FlowRequests(new Registry(), store, id -> { }, rec -> null);
        assertThrows(FlowException.class, () -> fr.waitingRun("nope"));
        RunRecord rec = new RunRecord("r", "u", Json.M.createObjectNode(), Json.M.createObjectNode());
        store.save(rec);
        FlowException e = assertThrows(FlowException.class, () -> fr.waitingRun("r"));
        assertTrue(e.getMessage().contains("not waiting"));
    }
}

package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ToolInputValidationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final JsonSchemaToolInputValidator validator = new JsonSchemaToolInputValidator();

    @TempDir
    Path base;

    private static SimpleTool tool(List<String> ran) {
        return new SimpleTool("count", "counts", SimpleTool.schema("path", "a path", "n:integer?", "how many",
                "flag:boolean?", "a flag"), "Misc", ToolKind.READ_ONLY,
                (c, i) -> {
                    ran.add(i.toString());
                    return "ran";
                });
    }

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void schemaWithOnlyOptionalFieldsHasNoRequiredArray() {
        assertFalse(SimpleTool.schema("a?", "x").has("required"));
    }

    @Test
    void validInputPasses() throws Exception {
        var o = validator.validate(tool(new ArrayList<>()), json("{\"path\":\"a\",\"n\":3}"), ValidationMode.STRICT);
        assertTrue(o.valid(), o.errors().toString());
    }

    @Test
    void missingRequiredAndWrongTypeAreReported() throws Exception {
        var o = validator.validate(tool(new ArrayList<>()), json("{\"n\":\"x\"}"), ValidationMode.LENIENT);
        assertFalse(o.valid());
        assertEquals(2, o.errors().size(), o.errors().toString());
    }

    @Test
    void lenientRepairsStringsAndNulls() throws Exception {
        var o = validator.validate(tool(new ArrayList<>()),
                json("{\"path\":\"a\",\"n\":\"5\",\"flag\":\"TRUE\",\"extra\":null}"), ValidationMode.LENIENT);
        assertTrue(o.valid(), o.errors().toString());
        assertEquals(5, o.input().get("n").asInt());
        assertTrue(o.input().get("flag").isBoolean());
        var n = validator.validate(tool(new ArrayList<>()), json("{\"path\":\"a\",\"n\":null}"), ValidationMode.LENIENT);
        assertTrue(n.valid());
        assertFalse(n.input().has("n"));
    }

    @Test
    void strictDoesNotRepair() throws Exception {
        var o = validator.validate(tool(new ArrayList<>()), json("{\"path\":\"a\",\"n\":\"5\"}"), ValidationMode.STRICT);
        assertFalse(o.valid());
    }

    @Test
    void offAcceptsAnything() throws Exception {
        assertTrue(validator.validate(tool(new ArrayList<>()), json("{}"), ValidationMode.OFF).valid());
    }

    @Test
    void modeParsesAndDefaultsToLenient() {
        assertEquals(ValidationMode.LENIENT, ValidationMode.parse(null));
        assertEquals(ValidationMode.STRICT, ValidationMode.parse("Strict"));
        assertEquals(ValidationMode.OFF, ValidationMode.parse("off"));
        assertThrows(IllegalArgumentException.class, () -> ValidationMode.parse("loose"));
    }

    @Test
    void everyBuiltInToolSchemaCompilesAndRejectsAnEmptyCallWhenItHasRequiredFields() throws Exception {
        AgentHarness h = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base)).build();
        var factory = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V7);
        int checked = 0;
        for (HarnessTool t : h.tools().all()) {
            assertDoesNotThrow(() -> factory.getSchema(t.inputSchema()), "schema of " + t.name());
            boolean hasRequired = t.inputSchema().path("required").size() > 0;
            var o = validator.validate(t, json("{}"), ValidationMode.STRICT);
            assertEquals(hasRequired, !o.valid(), t.name() + ": " + o.errors());
            checked++;
        }
        assertTrue(checked > 5, "expected the standard tools, got " + checked);
    }

    @Test
    void mcpStyleSchemaIsEnforcedOrSkippedNeverThrown() throws Exception {
        JsonNode schema = json("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                + "\"properties\":{\"q\":{\"$ref\":\"#/$defs/query\"}},\"required\":[\"q\"],"
                + "\"additionalProperties\":false,\"$defs\":{\"query\":{\"type\":\"string\",\"minLength\":2}}}");
        HarnessTool mcp = new SimpleTool("mcp__s__search", "d", schema, "S", ToolKind.OTHER, (c, i) -> "ok");

        assertTrue(validator.validate(mcp, json("{\"q\":\"ab\"}"), ValidationMode.LENIENT).valid());
        // either rejected (schema enforced) or accepted (schema skipped): both are acceptable, a crash is not
        assertDoesNotThrow(() -> validator.validate(mcp, json("{\"q\":\"a\",\"zz\":1}"), ValidationMode.LENIENT));
    }

    private List<AgentEvent> run(AgentHarness h, String chat) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat, 0, ev -> {
            events.add(ev);
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(20, TimeUnit.SECONDS));
        return events;
    }

    private static String output(List<AgentEvent> events) {
        return events.stream().filter(e -> e.type().equals("tool_result")).findFirst().orElseThrow()
                .data().path("output").asText();
    }

    @Test
    void invalidCallNeverReachesTheTool() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "count", "{\"n\":1}"), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools()
                .tools(List.of(tool(ran))).build();

        String out = output(run(h, h.sessions().create(null).chatId()));

        assertTrue(out.startsWith("Invalid arguments for 'count'"), out);
        assertTrue(ran.isEmpty());
    }

    @Test
    void lenientIsTheDefaultAndRepairsBeforeTheToolRuns() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "count", "{\"path\":\"a\",\"n\":\"2\"}"), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools()
                .tools(List.of(tool(ran))).build();
        String chat = h.sessions().create(null).chatId();

        assertEquals(ValidationMode.LENIENT, h.toolValidation(chat));
        assertEquals("ran", output(run(h, chat)));
        assertEquals("{\"path\":\"a\",\"n\":2}", ran.get(0));
    }

    @Test
    void chatOverrideSwitchesToStrict() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel model = new ScriptedModel().enqueue(toolUse("t1", "count", "{\"path\":\"a\",\"n\":\"2\"}"), text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools()
                .tools(List.of(tool(ran))).build();
        String chat = h.sessions().create(null).chatId();
        h.setToolValidation(chat, ValidationMode.STRICT);

        assertTrue(output(run(h, chat)).startsWith("Invalid arguments"));
        assertTrue(ran.isEmpty());
    }

    @Test
    void addedValidatorRunsAfterTheDefaultAndReplacementReplacesIt() throws Exception {
        ToolInputValidator noRoot = (t, in, mode) -> in.path("path").asText().startsWith("/")
                ? ToolInputValidator.Outcome.invalid(in, List.of("absolute paths are not allowed"))
                : ToolInputValidator.Outcome.ok(in);

        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel m1 = new ScriptedModel().enqueue(toolUse("t1", "count", "{\"path\":\"/etc\"}"), text("ok"));
        AgentHarness added = AgentHarness.builder().modelClient(m1).config(config(base)).withoutStandardTools()
                .tools(List.of(tool(ran))).addToolInputValidator(noRoot).build();
        String out = output(run(added, added.sessions().create(null).chatId()));
        assertTrue(out.contains("absolute paths are not allowed"), out);

        // the replacement alone: the schema is no longer enforced
        ScriptedModel m2 = new ScriptedModel().enqueue(toolUse("t1", "count", "{}"), text("ok"));
        AgentHarness replaced = AgentHarness.builder().modelClient(m2).config(config(base)).withoutStandardTools()
                .tools(List.of(tool(ran))).toolInputValidator(noRoot).build();
        assertEquals("ran", output(run(replaced, replaced.sessions().create(null).chatId())));
    }
}

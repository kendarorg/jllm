package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** The sample flow {@code runConfigs/unsafe/flows/develop-app.json}, with a scripted model and a real test command. */
class DevelopAppFlowTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final Path FLOW = Path.of("..", "..", "runConfigs", "unsafe", "flows", "develop-app.json");

    @TempDir
    Path project;

    private static Registry builtins() {
        Registry r = new Registry();
        new BuiltinActions().register(r);
        return r;
    }

    @Test
    void sampleFlowIsValid() throws Exception {
        FlowDef f = FlowDef.load(FLOW);
        assertEquals(List.of(), FlowValidator.check(f, builtins()));
        assertTrue(Workflows.inputs(f).containsAll(List.of("request", "projectDir", "testCommand")), Workflows.inputs(f).toString());
    }

    @Test
    void gatesUseStructuredValuesAndRealExitCodes() throws Exception {
        List<String> tasks = new ArrayList<>();
        int[] reviews = {0};
        Registry reg = builtins().model(c -> {
            String p = c.user();
            if (p.startsWith("Application to build")) return tag(tasks, "plan", "the plan");
            if (p.startsWith("Request:")) return tag(tasks, "implement", "implemented");
            if (p.contains("Review the code")) {
                tag(tasks, "review", "");
                // the first review praises in prose but does not approve: the gate must not read the prose
                return reviews[0]++ == 0 ? "{\"approved\": false, \"issues\": [\"APPROVED overall, but add input checks\"]}"
                        : "{\"approved\": true, \"issues\": []}";
            }
            if (p.startsWith("Plan:") && p.contains("Review findings")) return tag(tasks, "rework", "fixed findings");
            if (p.contains("Add the tests that are missing")) return tag(tasks, "test", "tests added; all PASSED");
            if (p.contains("ended with exit code")) {
                assertTrue(p.contains("tests failed"), "fixtests must get the real output tail: " + p);
                assertTrue(p.contains("exit code 1"), p);
                Files.writeString(project.resolve("fixed.txt"), "ok");
                return tag(tasks, "fixtests", "fixed");
            }
            throw new AssertionError("unexpected prompt: " + p);
        });
        ObjectNode in = Json.M.createObjectNode();
        in.put("request", "a calculator").put("projectDir", project.toString());
        in.put("testCommand", WINDOWS ? "if exist fixed.txt (echo all tests passed) else (echo tests failed& exit 1)"
                : "if [ -f fixed.txt ]; then echo all tests passed; else echo tests failed; exit 1; fi");

        var r = FlowEngine.simple(reg).run(FlowDef.load(FLOW), in, "u");

        assertEquals(FlowEngine.Status.COMPLETED, r.status(), String.valueOf(r.error()));
        assertEquals(List.of("plan", "implement", "review", "rework", "review", "test", "fixtests"), tasks);
        assertTrue(r.result().path("testOutput").asText().contains("all tests passed"), r.result().toString());

        List<JsonNode> decisions = r.history().stream().filter(e -> e.kind().equals("decision")).map(FlowEngine.Event::data).toList();
        assertEquals(List.of("changes", "approved", "failed", "passed"),
                decisions.stream().map(d -> d.path("outcome").asText()).toList());
        for (JsonNode d : decisions) {
            assertEquals("java", d.path("decidedBy").asText());
            assertTrue(d.path("detail").has("value"), d.toString());
            assertFalse(d.path("detail").path("reason").asText().isBlank(), d.toString());
            assertFalse(d.path("detail").path("fellBack").booleanValue(), d.toString());
        }
        assertFalse(decisions.get(0).path("detail").path("value").booleanValue());
        assertEquals(1, decisions.get(2).path("detail").path("value").asInt());
        assertEquals(0, decisions.get(3).path("detail").path("value").asInt());
    }

    private static String tag(List<String> tasks, String name, String reply) {
        tasks.add(name);
        return reply;
    }
}

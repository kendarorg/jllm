package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.workflows.WorkflowApi;
import org.kendar.llm.workflows.ActionProvider;
import org.kendar.llm.workflows.FlowStore;
import org.kendar.llm.workflows.RunStore;
import org.kendar.llm.workflows.WorkflowApiImpl;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SampleFlowsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String U = SampleFlows.USER;
    @TempDir
    Path dir;
    WorkflowApi api;

    @BeforeEach
    void setUp() {
        SampleFlows.install(dir);
        api = WorkflowApiImpl.create(ActionProvider.load(getClass().getClassLoader()),
                new RunStore.Files(dir.resolve("runs")), new FlowStore.Files(dir.resolve("flows")), "admin");
    }

    private List<String> executions() {
        return SampleFlowActions.executions(dir.resolve("runs"));
    }

    private JsonNode start(String flow, String input) throws Exception {
        return api.start(U, Set.of(), flow, "personal", M.readTree(input), null);
    }

    @Test
    void allFlowsPassTheSaveTimeChecks() throws Exception {
        for (var e : SampleFlows.all().entrySet()) {
            JsonNode flow = M.readTree(e.getValue().replace('\'', '"'));
            assertEquals(0, api.validate(U, Set.of(), "personal", flow).path("errors").size(), e.getKey());
        }
        assertEquals(4, api.list(U, Set.of()).size());
    }

    @Test
    void linearFlowCompletesWithoutAsking() throws Exception {
        JsonNode run = start("linear", "{\"text\":\"  hello big world \"}");
        assertEquals("completed", run.path("status").asText(), run.toString());
        assertEquals("HELLO BIG WORLD", run.at("/result/upper").asText());
        assertEquals("3", run.at("/result/words").asText());
        assertEquals(List.of("normalizeText", "countWords"), executions());
    }

    @Test
    void branchingFlowTakesTheRightBranch() throws Exception {
        assertEquals("approved", start("branching", "{\"amount\":50}").at("/result/decision").asText());
        assertEquals("review", start("branching", "{\"amount\":500}").at("/result/decision").asText());
        assertEquals("rejected", start("branching", "{\"amount\":5000}").at("/result/decision").asText());
        assertEquals(List.of("amountBand:small", "approve", "amountBand:large", "flagReview", "amountBand:huge"),
                executions());
    }

    @Test
    void askingFlowWaitsForTwoAnswersThenCallsTheSubFlow() throws Exception {
        JsonNode run = start("ask-and-call", "{}");
        assertEquals("waiting", run.path("status").asText(), run.toString());
        assertTrue(run.toString().contains("Which order number?"), run.toString());
        String id = run.path("runId").asText();

        assertThrows(RuntimeException.class, () -> api.answer(U, Set.of(), id, new TextNode("bad")),
                "the pattern refuses the answer");
        run = api.answer(U, Set.of(), id, new TextNode("A-1001"));
        assertEquals("waiting", run.path("status").asText(), run.toString());
        assertTrue(run.toString().contains("How should we pay you back?"), run.toString());
        assertTrue(executions().isEmpty(), "the sub-flow has not run yet");

        run = api.answer(U, Set.of(), id, new TextNode("credit"));
        assertEquals("completed", run.path("status").asText(), run.toString());
        assertEquals("A-1001", run.at("/result/order").asText());
        assertEquals("credit", run.at("/result/how").asText());
        assertEquals("shipped", run.at("/result/status").asText());
        assertEquals(List.of("loadOrder:A-1001"), executions());
    }
}

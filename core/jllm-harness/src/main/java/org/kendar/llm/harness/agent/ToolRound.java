package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The tool calls of one model response: run in parallel, cancelled together, answered in one user message. */
final class ToolRound {
    private final AgentLoop.Deps deps;
    private final ToolExecutor executor;
    private final RunGuards guards;
    private final TurnInjections injections;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    ToolRound(AgentLoop.Deps deps, ToolExecutor executor, RunGuards guards, TurnInjections injections) {
        this.deps = deps;
        this.executor = executor;
        this.guards = guards;
        this.injections = injections;
    }

    /**
     * Runs the calls and appends their results to the log.
     *
     * @param cut the call a max_tokens stop cut off (answered with an error, not run); null when none
     * @return the result that ends the run (the agent is stuck), or null when the loop goes on
     */
    AgentLoop.LoopResult run(LoopRun run, Map<String, HarnessTool> byName, Set<String> refused,
                             List<JsonNode> toolUses, JsonNode cut, boolean windowFull) {
        LoopRequest req = run.request;
        var mapper = deps.mapper();
        var messages = req.messages();
        String intent = lastUserText(messages);
        run.scope.intent = intent;
        List<Future<ObjectNode>> futures = new ArrayList<>();
        for (JsonNode use : toolUses) {
            futures.add(threads.submit(() -> executor.execute(use, byName, refused, run.toolContext, req.mode(),
                    req.sink(), req.cancelled(), req.interactive(), intent, req.label())));
        }
        // cancel interrupts the running tool threads; every tool_use still gets a tool_result below
        Runnable unhook = run.token.onCancel(() -> futures.forEach(f -> f.cancel(true)));
        ObjectNode resultMsg = messages.addObject();
        resultMsg.put("role", "user");
        ArrayNode results = resultMsg.putArray("content");
        try {
            for (int i = 0; i < futures.size(); i++) {
                String id = toolUses.get(i).path("id").asText();
                try {
                    results.add(futures.get(i).get());
                } catch (CancellationException e) {
                    results.add(ToolResults.of(mapper, id, "Cancelled by user", true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    futures.forEach(f -> f.cancel(true));
                    results.add(ToolResults.of(mapper, id, "Cancelled by user", true));
                } catch (Exception e) { // boundary: tool boundary: any failure becomes the error result of the call
                    results.add(ToolResults.of(mapper, id, "Tool execution failed: " + e.getMessage(), true));
                }
            }
        } finally {
            unhook.run();
        }
        if (cut != null) results.add(guards.truncatedResult(cut, windowFull, run.toolContext));
        StuckDetector.Detection loopSeen = run.stuck.record(toolUses, results);
        if (loopSeen != null && guards.onStuck(loopSeen, run.stuck, results, req.chatId(), req.sink(),
                req.cancelled(), req.interactive(), req.label())) {
            if (req.persist()) deps.sessions().saveMessages(req.chatId(), messages);
            return new AgentLoop.LoopResult(run.finalText, AgentLoop.Outcome.STUCK);
        }
        injections.scoped(messages, toolUses, results, byName, run.toolContext, req.sink(), req.label(),
                req.chatId());
        if (req.persist()) deps.sessions().saveMessages(req.chatId(), messages);
        // every tool_use has its tool_result: a safe boundary for the user's steering messages
        injections.steering(req.inbox(), messages, req.sink(), req.chatId(), req.persist());
        return null;
    }

    /** The newest plain user message: what the classifier is told the user wants. */
    private static String lastUserText(ArrayNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if (!"user".equals(m.path("role").asText())) continue;
            JsonNode c = m.path("content");
            String t = c.isTextual() ? c.asText()
                    : c.isArray() && !c.isEmpty() && "text".equals(c.get(0).path("type").asText())
                    ? c.get(0).path("text").asText() : null;
            // the harness's own continue request is not the user's intent
            if (t != null && !t.startsWith(AgentLoop.CONTINUE_NOTE)) return t;
        }
        return "";
    }
}

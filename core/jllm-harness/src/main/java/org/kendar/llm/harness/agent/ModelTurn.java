package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.ContextView;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.context.compaction.CutPlanner;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.llm.ModelCancelledException;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.ThinkingLevel;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One model call of the loop and what the answer means: the request, streaming, the retry after a context overflow,
 * the continuation of a response cut off at max_tokens, refusals and the end of the turn.
 */
final class ModelTurn {
    private static final Logger log = LoggerFactory.getLogger(ModelTurn.class);

    private final AgentLoop.Deps deps;
    private final RunGuards guards;
    private final TurnInjections injections;

    ModelTurn(AgentLoop.Deps deps, RunGuards guards, TurnInjections injections) {
        this.deps = deps;
        this.guards = guards;
        this.injections = injections;
    }

    /** The call's result: the response, a result that ends the run, or a retry of the same turn. */
    record Reply(ModelResponse response, AgentLoop.LoopResult done, boolean retry) {
    }

    /** What the loop does after a response: end the run, take another turn, or run the tool calls. */
    record Step(AgentLoop.LoopResult done, boolean again, List<JsonNode> toolUses, JsonNode cut, boolean windowFull) {
        static Step end(AgentLoop.LoopResult r) {
            return new Step(r, false, List.of(), null, false);
        }

        static Step next() {
            return new Step(null, true, List.of(), null, false);
        }
    }

    /** Sends the request of a turn; a failure that ends the run or a context overflow that retries is in the reply. */
    Reply call(LoopRun run, List<HarnessTool> tools, RequestPrefix prefix, ContextView view,
               Consumer<Usage> spent) {
        LoopRequest req = run.request;
        var mapper = deps.mapper();
        var metrics = deps.metrics();
        var config = deps.config();
        var sink = req.sink();
        var cancelled = req.cancelled();
        var token = run.token;
        // checked on what is sent: a strategy may leave reasoning out of the view without touching the log
        if (run.turnHadThinking && !run.thinkingOff && !CutPlanner.currentTurnHasThinking(view.messages())) {
            run.thinkingOff = true;
            sink.emit("warning", mapper.createObjectNode().put("message",
                    "Context compacted mid-turn: thinking is off for the rest of this turn"));
        }

        metrics.turn();
        ModelResponse resp;
        long modelStart = System.nanoTime();
        HookResult calling = deps.hooks().run(HookContext.before(HookEvent.PRE_LLM, req.chatId(), req.agent(), null));
        if (calling.denied()) {
            // nothing was appended for this turn, so the message log stays consistent
            sink.emit("error", mapper.createObjectNode()
                    .put("message", "Model call blocked by a hook: " + calling.denyReason()));
            return new Reply(null, new AgentLoop.LoopResult(run.finalText, AgentLoop.Outcome.FAILED), false);
        }
        try {
            ModelRequest request = new ModelRequest(req.pinned(), run.turnExtra, view.messages(), tools,
                    config.maxTokens(), null, !config.parallelToolUse(), null, null);
            if (run.thinkingOff) request = request.withThinkingLevel(ThinkingLevel.MINIMAL);
            token.onRetry((status, attempt, max, delayMs) -> sink.emit("retry", mapper.createObjectNode()
                    .put("status", status).put("attempt", attempt).put("max", max).put("delayMs", delayMs)
                    .put("message", "Model call failed" + (status > 0 ? " (HTTP " + status + ")" : "")
                            + ", retry " + attempt + "/" + max + " in " + Math.max(1, delayMs / 1000) + " s")));
            // deltas are for display only: the response is used once it is complete, as without streaming. Only
            // the main agent's are shown (a sub-agent's events stay out of the chat stream)
            DeltaCoalescer deltas = req.label() == null ? new DeltaCoalescer(sink, mapper, null) : null;
            try {
                resp = deltas == null ? req.client().complete(request, token) : req.client().stream(request, token, deltas);
            } finally {
                if (deltas != null) deltas.flush();
            }
            metrics.modelCall(Duration.ofNanos(System.nanoTime() - modelStart), "ok");
            deps.hooks().run(HookContext.afterLlm(req.chatId(), req.agent(), responseText(resp), false,
                    (System.nanoTime() - modelStart) / 1_000_000L, resp.usage()));
        } catch (RuntimeException e) { // boundary: model call boundary: reported to the hooks, handled below
            deps.hooks().run(HookContext.afterLlm(req.chatId(), req.agent(), String.valueOf(e.getMessage()), true,
                    (System.nanoTime() - modelStart) / 1_000_000L, null));
            if (e instanceof ModelCancelledException || cancelled.getAsBoolean()) {
                // nothing was appended for this turn, so the message log stays consistent
                metrics.modelCall(Duration.ofNanos(System.nanoTime() - modelStart), "cancelled");
                sink.emit("cancelled", mapper.createObjectNode());
                return new Reply(null, new AgentLoop.LoopResult(run.finalText, AgentLoop.Outcome.CANCELLED), false);
            }
            if (!run.overflowRetried && ModelException.isContextOverflow(e)) {
                // rejected as too long: let the strategy shrink the request once; a second rejection is an error
                run.overflowRetried = true;
                ContextView retry = deps.context().onOverflow(new ContextTurn(req.chatId(), run.contextKey,
                        req.messages(), req.inherited(), null, prefix, sink, cancelled, spent, req.persist()));
                if (retry != null) {
                    if (retry.logChanged() && req.persist()) deps.sessions().saveMessages(req.chatId(), req.messages());
                    run.last = null;
                    return new Reply(null, null, true);
                }
            }
            metrics.modelCall(Duration.ofNanos(System.nanoTime() - modelStart), "error");
            log.warn("Model call failed: {}", e.getMessage());
            sink.emit("error", mapper.createObjectNode().put("message", String.valueOf(e.getMessage())));
            return new Reply(null, new AgentLoop.LoopResult(run.finalText, AgentLoop.Outcome.FAILED), false);
        }
        run.overflowRetried = false;
        run.last = resp.usage();
        metrics.tokens(resp.usage());
        if (req.persist()) deps.sessions().addUsage(req.chatId(), resp.usage());
        sink.emit("usage", mapper.valueToTree(resp.usage()));
        return new Reply(resp, null, false);
    }

    private static String responseText(ModelResponse resp) {
        StringBuilder text = new StringBuilder();
        for (JsonNode b : resp.content()) {
            if ("text".equals(b.path("type").asText())) text.append(b.path("text").asText());
        }
        return text.toString();
    }

    /** Appends the answer to the log and decides from its stop reason how the loop goes on. */
    Step handle(LoopRun run, ModelResponse resp) {
        LoopRequest req = run.request;
        var mapper = deps.mapper();
        var config = deps.config();
        var sessions = deps.sessions();
        var sink = req.sink();
        var messages = req.messages();
        String chatId = req.chatId();
        boolean persist = req.persist();
        ObjectNode assistant = messages.addObject();
        assistant.put("role", "assistant");
        assistant.set("content", resp.content());

        StringBuilder text = new StringBuilder();
        List<JsonNode> toolUses = new ArrayList<>();
        for (JsonNode b : resp.content()) {
            String type = b.path("type").asText();
            if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                run.turnHadThinking = true;
            } else if ("text".equals(type)) {
                text.append(b.path("text").asText());
                sink.emit("text", mapper.createObjectNode().put("text", b.path("text").asText()));
            } else if ("tool_use".equals(type)) {
                toolUses.add(b);
            }
        }
        if (!text.isEmpty()) run.finalText = run.carried + text;
        run.carried = "";
        if (persist) sessions.saveMessages(chatId, messages);

        String stop = resp.stopReason();
        if ("pause_turn".equals(stop)) {
            return Step.next(); // server asked us to resume with the assistant content as-is
        }
        JsonNode cut = null;
        boolean windowFull = false;
        if ("max_tokens".equals(stop)) {
            // only the last block can be cut off: the tool calls before it are complete and still run
            JsonNode lastBlock = resp.content().isEmpty() ? null : resp.content().get(resp.content().size() - 1);
            if (lastBlock != null && "tool_use".equals(lastBlock.path("type").asText())) cut = lastBlock;
            int complete = toolUses.size() - (cut == null ? 0 : 1);
            windowFull = guards.stoppedByWindow(resp.usage());
            // a response that is only a cut-off call is retried once; twice in a row stops the run
            boolean resume = !toolUses.isEmpty() && (complete > 0 || !run.truncationRetried);
            // cut-off text is continued; a full window would only cut the continuation again
            boolean continueText = toolUses.isEmpty() && !windowFull && run.continuations < AgentLoop.MAX_CONTINUATIONS;
            sink.emit("warning", mapper.createObjectNode().put("message", (windowFull
                    ? "The response stopped at " + resp.usage().outputTokens() + " output tokens, below max_tokens ("
                    + config.maxTokens() + "): the server's context window is full."
                    : "The response hit max_tokens (" + config.maxTokens() + ") and was cut off.")
                    + (cut != null ? " Its last tool call (" + cut.path("name").asText()
                    + ") is incomplete and was not run." : "")
                    + (complete > 0 ? " Running the " + complete + " complete tool call(s)." : "")
                    + (continueText ? " Asking the model to continue." : "")));
            if (toolUses.isEmpty()) {
                if (!continueText) return Step.end(new AgentLoop.LoopResult(run.finalText, AgentLoop.Outcome.TRUNCATED));
                run.continuations++;
                run.carried = run.finalText;
                ObjectNode more = messages.addObject();
                more.put("role", "user");
                more.putArray("content").addObject().put("type", "text").put("text", AgentLoop.CONTINUE_NOTE
                        + " Your previous response was cut off at the output limit of " + config.maxTokens()
                        + " tokens. Continue exactly where it stopped, without repeating what you already wrote.");
                if (persist) sessions.saveMessages(chatId, messages);
                return Step.next();
            }
            run.continuations = 0;
            if (!resume) {
                // keep tool_use/tool_result pairing valid for the next turn
                ObjectNode fix = messages.addObject();
                fix.put("role", "user");
                fix.putArray("content").add(guards.truncatedResult(cut, windowFull, run.toolContext));
                if (persist) sessions.saveMessages(chatId, messages);
                return Step.end(new AgentLoop.LoopResult(run.finalText, AgentLoop.Outcome.TRUNCATED));
            }
            run.truncationRetried = complete == 0;
            if (cut != null) toolUses.remove(toolUses.size() - 1);
        } else {
            run.truncationRetried = false;
            run.continuations = 0;
            if ("refusal".equals(stop) || "model_context_window_exceeded".equals(stop)) {
                sink.emit("warning", mapper.createObjectNode().put("message",
                        "refusal".equals(stop) ? "The model declined to answer (refusal)."
                                : "The model context window was exceeded."));
                if (!toolUses.isEmpty()) {
                    // the calls are not run: answer each one, or every later request is rejected
                    ObjectNode fix = messages.addObject();
                    fix.put("role", "user");
                    ArrayNode fixed = fix.putArray("content");
                    for (JsonNode use : toolUses) {
                        fixed.add(ToolResults.of(mapper, use.path("id").asText(),
                                "Not executed: the response ended with " + stop + ".", true));
                    }
                    if (persist) sessions.saveMessages(chatId, messages);
                }
                return Step.end(new AgentLoop.LoopResult(run.finalText,
                        "refusal".equals(stop) ? AgentLoop.Outcome.REFUSED : AgentLoop.Outcome.TRUNCATED));
            }
            // end_turn and stop_sequence both simply end the turn
            if (!"tool_use".equals(stop) || toolUses.isEmpty()) {
                // queued steering is not lost when the answer was final: it becomes the next turn
                if (injections.steering(req.inbox(), messages, sink, chatId, persist)
                        || injections.followUp(req.inbox(), messages, sink, chatId, persist)) {
                    return Step.next();
                }
                AgentLoop.Outcome checked = guards.stopCheck(chatId, run.agentName, run.finalText, req.mode(),
                        messages, sink, req.cancelled(), persist, run.stopAttempts, run.stuck);
                if (checked == null) return Step.next();
                guards.clear(chatId, run.agentName);
                return Step.end(new AgentLoop.LoopResult(run.finalText, checked));
            }
        }
        return new Step(null, false, toolUses, cut, windowFull);
    }
}

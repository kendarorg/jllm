package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.ToolContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * What keeps a run of the loop in bounds: the calls the user rejected, the tools that changed things, the stop
 * check, the stuck detector, the turn limit and the max_tokens accounting.
 */
public final class RunGuards {
    private final AgentLoop.Deps deps;
    /** Calls (tool name + input) the user rejected in the current run, per chat + agent name. */
    private final Map<String, Set<String>> rejectedCalls = new ConcurrentHashMap<>();
    /** Agents (chat + agent name) in which a tool that changes things ran during the current run. */
    private final Set<String> changed = ConcurrentHashMap.newKeySet();

    RunGuards(AgentLoop.Deps deps) {
        this.deps = deps;
    }

    private static String key(String chatId, String agent) {
        return chatId + "\u0000" + (agent == null ? HookContext.MAIN : agent);
    }

    /** Forgets the rejected calls and the changes of the agent: a run starts or ends. */
    void clear(String chatId, String agent) {
        String key = key(chatId, agent);
        changed.remove(key);
        rejectedCalls.remove(key);
    }

    void markChanged(String chatId, String agent) {
        changed.add(key(chatId, agent));
    }

    /** Whether the user rejected this exact call earlier in the run of the agent. */
    boolean wasRejected(String chatId, String agent, String call) {
        Set<String> seen = rejectedCalls.get(key(chatId, agent));
        return seen != null && seen.contains(call);
    }

    void reject(String chatId, String agent, String call) {
        rejectedCalls.computeIfAbsent(key(chatId, agent), k -> ConcurrentHashMap.newKeySet()).add(call);
    }

    /**
     * Whether a tool that may change things (anything not read-only: file edits, shell, scripts) ran in the current
     * run of the agent. {@code agent} is {@value HookContext#MAIN} or a sub-agent label, as in
     * {@link HookContext#agent()} of {@code STOP}. Meant for STOP hooks ({@code onlyIfChanged}).
     */
    public boolean changedInRun(String chatId, String agent) {
        return changed.contains(key(chatId, agent));
    }

    /**
     * Fires STOP for a completion attempt.
     *
     * @return the outcome that ends the run, or null when a hook vetoed the stop and the agent goes on (the veto
     * message was appended to the log)
     */
    AgentLoop.Outcome stopCheck(String chatId, String agent, String answer, PermissionMode mode, ArrayNode messages,
                                EventSink sink, BooleanSupplier cancelled, boolean persist, int[] attempts,
                                StuckDetector stuck) {
        var mapper = deps.mapper();
        // plan mode changed nothing to verify; a cancelled run is not checked
        if (mode == PermissionMode.PLAN || cancelled.getAsBoolean()) return AgentLoop.Outcome.COMPLETED;
        int attempt = ++attempts[0];
        HookResult r = deps.hooks().run(HookContext.stop(chatId, agent, answer, attempt));
        if (!r.continues()) return AgentLoop.Outcome.COMPLETED;
        String message = r.continueWith();
        int max = deps.config().loop().verify().maxReentries();
        if (stuck.verifyRepeated(message)) {
            sink.emit("stuck", mapper.createObjectNode().put("pattern", "verify_repeat").put("tool", "")
                    .put("count", 2).put("action", "stop").put("agent", agent)
                    .put("message", "The stop check failed twice with the same output"));
            sink.emit("warning", mapper.createObjectNode().put("message", "The stop check failed twice with the same "
                    + "output: stopping, the result is not verified."));
            return AgentLoop.Outcome.UNVERIFIED;
        }
        if (attempt > max) {
            sink.emit("warning", mapper.createObjectNode().put("message", "The stop check still fails after " + max
                    + " re-entr" + (max == 1 ? "y" : "ies") + ": the result is not verified."));
            return AgentLoop.Outcome.UNVERIFIED;
        }
        TurnInjections.appendUserText(messages, AgentLoop.VERIFY_NOTE + "\n\n" + message);
        if (persist) deps.sessions().saveMessages(chatId, messages);
        sink.emit("verify_failed", mapper.createObjectNode().put("agent", agent).put("attempt", attempt)
                .put("message", message));
        return null;
    }

    /** What the loop does after the stuck detector fired. */
    enum StuckAction {
        /** Go on: dismissed by the confirming model, nudged, or the user let the agent continue. */
        GO_ON,
        /** One more turn with tool use off, then the run ends as {@code STUCK}. */
        WRAP_UP,
        /** The run ends as {@code STUCK} now. */
        STOP
    }

    /**
     * The stuck detector fired. With {@code confirm} the fast model may first judge the steps progress (the
     * detection is then dropped). The first time the agent is nudged (a note appended to the tool results); after
     * that an interactive run asks the user, a headless one stops. A stop is a wrap-up turn when {@code wrapUp} is on.
     *
     * @param messages the run's message log, read by the confirming model
     */
    StuckAction onStuck(StuckDetector.Detection d, StuckDetector stuck, ArrayNode results, JsonNode messages,
                        String chatId, EventSink sink, BooleanSupplier cancelled, boolean interactive, String label) {
        var mapper = deps.mapper();
        var cfg = deps.config().loop().stuck();
        Function<String, ObjectNode> event = action -> {
            ObjectNode ev = mapper.createObjectNode().put("pattern", d.pattern()).put("tool", d.tool())
                    .put("count", d.count()).put("action", action).put("message", d.what());
            if (label != null) ev.put("agent", label);
            return ev;
        };
        if (cfg.confirm() && deps.stuckJudge() != null) {
            StuckJudge.Verdict v = deps.stuckJudge().judge(d.what(), messages);
            if (v.progress()) {
                stuck.dismiss();
                sink.emit("stuck", event.apply("dismissed").put("reason", v.reason()));
                return StuckAction.GO_ON;
            }
        }
        if (stuck.detections() == 1) {
            results.addObject().put("type", "text")
                    .put("text", Prompts.load("stuck-note.md").replace("{what}", d.what()));
            sink.emit("stuck", event.apply("nudge"));
            return StuckAction.GO_ON;
        }
        if (interactive && !cancelled.getAsBoolean()) {
            String title = (label == null ? "" : "[" + label + "] ") + "The agent seems stuck";
            ApprovalService.ApprovalDecision a = deps.approvals().await(chatId, "stuck", title, d.what()
                    + ".\n\nIt was already told once to change approach. Let it continue?", null, req -> {
                ObjectNode ev = mapper.valueToTree(req);
                if (label != null) ev.put("agent", label);
                sink.emit("approval_request", ev);
            }, cancelled);
            deps.metrics().approval("stuck", a.approved());
            if (a.approved()) {
                sink.emit("stuck", event.apply("continue"));
                return StuckAction.GO_ON;
            }
        }
        if (cfg.wrapUp() && !cancelled.getAsBoolean()) {
            results.addObject().put("type", "text")
                    .put("text", Prompts.load("stuck-wrapup.md").replace("{what}", d.what()));
            sink.emit("stuck", event.apply("wrap_up"));
            sink.emit("warning", mapper.createObjectNode().put("message", "Stopping: " + d.what()
                    + ". Asking for a final answer without tools."));
            return StuckAction.WRAP_UP;
        }
        sink.emit("stuck", event.apply("stop"));
        sink.emit("warning", mapper.createObjectNode().put("message", "Stopped: " + d.what() + "."));
        return StuckAction.STOP;
    }

    /**
     * The turn limit was reached: asks the user whether to go on for another {@code maxTurns}. Only an interactive run
     * asks; no answer (timeout, cancel, reject) stops the run as before.
     */
    boolean continueAfterLimit(RunBudget budget, String chatId, boolean interactive, EventSink sink,
                               BooleanSupplier cancelled, String label) {
        if (!interactive || cancelled.getAsBoolean()) return false;
        String title = (label == null ? "" : "[" + label + "] ") + "Reached " + budget.limit() + " turns";
        String preview = "The agent has used " + budget.describe() + " without finishing. How many more turns to allow?";
        ApprovalService.ApprovalDecision d = deps.approvals().await(chatId, "continue", title, preview,
                String.valueOf(deps.config().maxTurns()), a -> {
            ObjectNode ev = deps.mapper().valueToTree(a);
            if (label != null) ev.put("agent", label);
            sink.emit("approval_request", ev);
        }, cancelled);
        deps.metrics().approval("continue", d.approved());
        if (!d.approved()) return false;
        budget.extend(turnsAllowed(d.edited()));
        return true;
    }

    /** The turns the user typed for the continue question; the default {@code maxTurns} when empty or invalid. */
    private int turnsAllowed(String edited) {
        try {
            int n = Integer.parseInt(edited == null ? "" : edited.strip());
            return n > 0 ? Math.min(n, 10_000) : deps.config().maxTurns();
        } catch (NumberFormatException e) {
            return deps.config().maxTurns();
        }
    }

    /**
     * A max_tokens stop well below the output limit: the server ran out of context window (llama.cpp reports both as
     * "length"). Unknown when the server reports no output tokens.
     */
    boolean stoppedByWindow(Usage usage) {
        return usage != null && usage.outputTokens() > 0 && usage.outputTokens() < deps.config().maxTokens() * 9L / 10;
    }

    /** The error result of the tool call a max_tokens stop cut off; a cut-off write_file keeps what arrived. */
    ObjectNode truncatedResult(JsonNode cut, boolean windowFull, ToolContext ctx) {
        String saved = PartialWrite.save(cut, ctx);
        return ToolResults.of(deps.mapper(), cut.path("id").asText(),
                "Not executed: your response was cut off while writing this call ("
                + (windowFull ? "the context window is full" : "output limit of " + deps.config().maxTokens() + " tokens")
                + "), so its input is incomplete."
                + (saved != null ? saved : " Retry this one with less output per response: write large files in "
                + "parts, write_file with the first part then write_file append: true for the rest."), true);
    }
}

package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.auth.Identity;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.ContextView;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.context.noise.NoiseCommandPruner;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.instructions.ScopedInstructions;
import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.PermissionGate;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.permission.ToolClassifier;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.JsonSchemaToolInputValidator;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolInputValidator;
import org.kendar.llm.harness.util.Uuids;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** ReAct loop: compact → model → (tool_use → gate/approval → execute → tool_result)* until a non-tool_use stop. */
public class AgentLoop {
    /** Opens the user message that asks the model to continue a response cut off at max_tokens. */
    static final String CONTINUE_NOTE = "[harness: response cut off]";
    /** Cut-off text responses continued in a row before the run stops. */
    static final int MAX_CONTINUATIONS = 3;
    /** Opens the user message that re-enters the loop after a STOP hook vetoed the stop. */
    static final String VERIFY_NOTE = "The stop check failed. Fix the problem and finish again. If it cannot be fixed "
            + "with what you have, stop and say what is missing; do not invent files or content.";
    /** Result of a call the user rejected. */
    public static final String REJECTED = "User rejected. Do not retry. Tell the user and ask how to proceed.";
    /** Result of a call identical to one the user rejected earlier in the run: the user is not asked again. */
    public static final String REJECTED_AGAIN = "User already rejected. Do not retry. Tell the user and ask how to proceed.";

    /**
     * Everything the loop works with. Null values mean the default: no hooks, no classifier, the anonymous identity,
     * no harness or built-in file tools, no scoped instructions, the JSON schema validator, no noise pruner.
     *
     * @param requestToolGroup  on-demand tool groups; null disables them
     * @param subAgentRunLog    audit trail of sub-agent runs
     * @param systemPrompt      static system text sent with every request
     * @param hooks             callbacks around prompts and tool calls
     * @param classifier        in auto mode decides whether a tool call may run unasked; null = auto mode never asks
     * @param identity          who the calls of each chat are made for: per-user tools and approval
     * @param harnessTools      offered in every request next to the context strategy's (the todo tools when enabled)
     * @param builtinFileTools  built-in file tools (compared by identity): free from a script
     * @param scopedInstructions adds the instruction files of the directories the file tools work in; null = off
     * @param inputValidator    checks the arguments of tool calls
     * @param noisePruner       removes no-information shell commands from the context after each main-agent run
     * @param runPolicy         process level switches (unattended, skip permissions); null = {@link RunPolicy#NORMAL}
     * @param documentExtensions extensions of the files a document reader turns into text; null = none
     */
    public record Deps(HarnessConfig config, ModelClient model, ContextStrategy context, ToolSelector toolSelector,
                       PermissionGate gate, ApprovalService approvals, ChatSessionService sessions,
                       SandboxExecutor sandbox, HarnessToolRegistry registry, SubAgentTool subAgentTool,
                       RequestToolGroupTool requestToolGroup, SubAgentRunLog subAgentRunLog, ObjectMapper mapper,
                       HarnessMetrics metrics, String systemPrompt, Hooks hooks, ToolClassifier classifier,
                       Identity identity, Collection<HarnessTool> harnessTools,
                       Collection<HarnessTool> builtinFileTools, ScopedInstructions scopedInstructions,
                       ToolInputValidator inputValidator, NoiseCommandPruner noisePruner, StuckJudge stuckJudge,
                       RunPolicy runPolicy, java.util.function.Supplier<List<String>> documentExtensions) {
        public Deps {
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            hooks = hooks == null ? Hooks.NONE : hooks;
            identity = identity == null ? Identity.ANONYMOUS : identity;
            harnessTools = harnessTools == null ? List.of() : List.copyOf(harnessTools);
            Set<HarnessTool> files = Collections.newSetFromMap(new IdentityHashMap<>());
            if (builtinFileTools != null) files.addAll(builtinFileTools);
            builtinFileTools = files;
            inputValidator = inputValidator == null ? new JsonSchemaToolInputValidator() : inputValidator;
            runPolicy = runPolicy == null ? RunPolicy.NORMAL : runPolicy;
            documentExtensions = documentExtensions == null ? List::of : documentExtensions;
        }

        /** Who can answer the permission questions of a chat: nobody when the process or the user's role is unattended. */
        public RunAttendance attendanceOf(String chatId) {
            return runPolicy.unattended() || identity.policyOf(chatId).unattended()
                    ? RunAttendance.UNATTENDED : RunAttendance.ATTENDED;
        }

        /** The system text of a chat: the static one, then the instructions of the chat's role and user. */
        public String systemPromptFor(String chatId) {
            String own = identity.policyOf(chatId).instructionsText();
            return own.isBlank() ? systemPrompt : systemPrompt + "\n\n" + own;
        }
    }

    /**
     * How a run of the loop ended. {@code STUCK}: the stuck detector stopped a repeating agent; {@code UNVERIFIED}: a
     * STOP hook kept vetoing the stop and the re-entries ran out.
     */
    public enum Outcome { COMPLETED, TRUNCATED, REFUSED, MAX_TURNS, CANCELLED, FAILED, STUCK, UNVERIFIED }

    /** Final text of a run of the loop and how it ended. */
    record LoopResult(String text, Outcome outcome) {
    }

    /**
     * Messages typed while a run is active. Steering is applied at the next turn boundary (after the current round of
     * tool calls, or as the next turn when the answer in progress is final); a follow-up only when the agent would
     * otherwise finish.
     */
    public interface Inbox {
        /** @return the next queued steering message, or null */
        String pollSteering();

        /** @return the next queued follow-up, or null */
        String pollFollowUp();
    }

    private final Deps deps;
    private final TaintTracker taint;
    private final RunGuards guards;
    private final ToolOffer offer;
    private final ToolExecutor toolExecutor;
    private final TurnInjections injections;
    private final ModeNote modeNote;
    private final ModelTurn modelTurn;
    private final ToolRound toolRound;
    private final SubAgentRunner subAgents;

    public AgentLoop(Deps deps) {
        this.deps = Objects.requireNonNull(deps);
        this.taint = new TaintTracker(deps.sessions());
        this.guards = new RunGuards(deps);
        this.offer = new ToolOffer(deps);
        this.toolExecutor = new ToolExecutor(deps, offer, guards, taint);
        this.injections = new TurnInjections(deps);
        this.modeNote = new ModeNote(deps.sandbox());
        this.modelTurn = new ModelTurn(deps, guards, injections);
        this.toolRound = new ToolRound(deps, toolExecutor, guards, injections);
        this.subAgents = new SubAgentRunner(deps, offer, taint, modeNote, this::loop);
    }

    /** Taint state of the chats: untrusted tool output in the context. */
    public TaintTracker taint() {
        return taint;
    }

    /** The rejected calls and the changes of the current runs. */
    public RunGuards guards() {
        return guards;
    }

    /** Runs the tool calls of the model; holds the per-chat validation mode. */
    public ToolExecutor toolExecutor() {
        return toolExecutor;
    }

    /** The messages and instruction files added to the log between turns. */
    public TurnInjections injections() {
        return injections;
    }

    /** Sub-agents and agent tasks. */
    public SubAgentRunner subAgents() {
        return subAgents;
    }

    /**
     * Runs one user turn of a persisted chat to completion.
     *
     * @param inbox messages typed while the run is active; null = none
     * @return how the run ended; {@code FAILED} when a hook blocked the prompt or the agent
     */
    public Outcome run(String chatId, String userText, EventSink sink, BooleanSupplier cancelled, Inbox inbox) {
        var hooks = deps.hooks();
        var mapper = deps.mapper();
        var sessions = deps.sessions();
        HookResult prompt = hooks.run(HookContext.prompt(chatId, userText));
        if (prompt.denied()) {
            sink.emit("warning", mapper.createObjectNode()
                    .put("message", "Prompt blocked by a hook: " + prompt.denyReason()));
            return Outcome.FAILED;
        }
        HookResult entering = hooks.run(HookContext.before(HookEvent.PRE_AGENT, chatId, HookContext.MAIN, userText));
        if (entering.denied()) {
            sink.emit("warning", mapper.createObjectNode()
                    .put("message", "Agent blocked by a hook: " + entering.denyReason()));
            return Outcome.FAILED;
        }
        ChatSessionData session = sessions.get(chatId);
        ArrayNode messages = sessions.messages(session);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject().put("type", "text").put("text", userText);
        if (prompt.addedContext() != null) content.addObject().put("type", "text").put("text", prompt.addedContext());
        sessions.saveMessages(chatId, messages);
        // untrusted output read earlier keeps gating side effects while it is still in the context
        taint.prune(chatId, messages, true);

        String answer = "";
        boolean failed = true;
        Outcome outcome;
        try {
            // the role's modes limit the run, whatever mode the chat holds
            PermissionMode mode = deps.identity().policyOf(chatId).clampMode(PermissionMode.parse(session.mode()));
            LoopResult r = loop(new LoopRequest(chatId, messages, () -> offer.chatTools(chatId, userText),
                    sessions.pinnedPrefix(session), deps.systemPromptFor(chatId), mode, sink,
                    cancelled, true, deps.attendanceOf(chatId), HookContext.MAIN, deps.model(), new int[]{0}, null, inbox,
                    null));
            answer = r.text();
            outcome = r.outcome();
            failed = false;
        } finally {
            hooks.run(HookContext.after(HookEvent.POST_AGENT, chatId, HookContext.MAIN, answer, failed));
        }
        if (PermissionMode.parse(sessions.get(chatId).mode()) == PermissionMode.PLAN && !answer.isBlank()) {
            sink.emit("plan_ready", mapper.createObjectNode().put("message",
                    "Plan ready. Type /edit to let the agent change files, or /auto to run without asking."));
        }
        if (deps.noisePruner() != null) deps.noisePruner().afterTurn(chatId);
        return outcome;
    }

    /** Manual compaction of a persisted chat (the /compact command). */
    public CompactResult compact(String chatId, String focus) {
        var sessions = deps.sessions();
        var sandbox = deps.sandbox();
        ArrayNode messages = sessions.messages(sessions.get(chatId));
        ChatSessionData session = sessions.get(chatId);
        PermissionMode mode = PermissionMode.parse(session.mode());
        RequestPrefix prefix = null;
        if (sessions.hasToolGroups(chatId)) {
            String scratch = SubAgentRunner.scratchId(session.agentId());
            ToolContext t = new ToolContext(chatId, sandbox.workDir(chatId), sandbox.scratchDir(chatId, scratch),
                    mode == PermissionMode.PLAN, null, mode, null, sandbox.files(chatId, scratch));
            String extra = deps.systemPromptFor(chatId) + modeNote.of(mode, t);
            prefix = new RequestPrefix(sessions.pinnedPrefix(session), extra, offer.chatTools(chatId, ""),
                    deps.model(), !deps.config().parallelToolUse());
        }
        CompactResult r = deps.context().compactNow(new ContextTurn(chatId, session.agentId(), messages, null, null,
                prefix, null, null, u -> { deps.metrics().tokens(u); sessions.addUsage(chatId, u); }, true), focus);
        if (r.changed()) sessions.saveMessages(chatId, messages);
        return r;
    }

    LoopResult loop(LoopRequest req) {
        String chatId = req.chatId();
        String agent = req.agent();
        String scratchId = req.persist() ? SubAgentRunner.scratchId(deps.sessions().get(chatId).agentId())
                : SubAgentRunner.scratchId(agent.length() > 26 ? agent.substring(0, 26) : agent) + "-" + Uuids.v7String();
        // the main agent's context state lives as long as the chat; a sub-agent's only for this run
        String contextKey = req.persist() ? deps.sessions().get(chatId).agentId() : scratchId;
        try {
            return runLoop(req, scratchId, contextKey);
        } finally {
            if (!req.persist()) deps.context().release(chatId, contextKey);
        }
    }

    private LoopResult runLoop(LoopRequest req, String scratchId, String contextKey) {
        var sink = req.sink();
        var cancelled = req.cancelled();
        var messages = req.messages();
        var sandbox = deps.sandbox();
        String chatId = req.chatId();
        RunBudget budget = new RunBudget(deps.config().maxTurns(), deps.config().loop().innerCallsPerTurn());
        LoopRunScope scope = toolExecutor.scope(req.mode(), sink, cancelled, req.attendance(), req.label(), budget);
        ToolContext tctx = new ToolContext(chatId, sandbox.workDir(chatId), sandbox.scratchDir(chatId, scratchId),
                req.mode() == PermissionMode.PLAN, null, req.mode(), new AgentRun(sink, cancelled, contextKey),
                sandbox.files(chatId, scratchId)).withScope(scope).withLinks(deps.config().externalLinks())
                .withUnconfined(!sandbox.confinesPaths())
                .withHosts(new ChatHostApprover(deps.approvals(), deps.metrics(), deps.mapper(), chatId, sink,
                        cancelled, req.attendance().asks(), req.label()));
        // the mode goes after the cached system prefix, so switching modes does not invalidate the prompt cache
        String turnExtra = req.frame() != null ? req.frame().turnExtra() : req.extra() + modeNote.of(req.mode(), tctx);
        String agentName = req.label() == null ? HookContext.MAIN : req.label();
        LoopRun run = new LoopRun(req, contextKey, scope, tctx, turnExtra,
                cancelled instanceof CancelToken ct ? ct : CancelToken.NONE,
                new StuckDetector(deps.config().loop().stuck()), agentName, budget);
        guards.clear(chatId, agentName);
        while (!budget.exhausted() || guards.continueAfterLimit(budget, chatId, req.attendance().asks(), sink, cancelled,
                req.label())) {
            budget.startTurn();
            if (cancelled.getAsBoolean()) {
                sink.emit("cancelled", deps.mapper().createObjectNode());
                return new LoopResult(run.finalText, Outcome.CANCELLED);
            }
            List<HarnessTool> tools = req.toolSource().get();
            Map<String, HarnessTool> byName = new LinkedHashMap<>();
            Set<String> refused = new LinkedHashSet<>();
            tools.forEach(t -> {
                if (req.frame() == null || req.frame().allowed().test(t)) byName.put(t.name(), t);
                else refused.add(t.name());
            });
            scope.byName = byName;

            // a tool_use without its tool_result (an older log, a crash) makes every request fail: answer it now
            if (ToolPairingRepair.repair(messages, deps.mapper()) && req.persist()) {
                deps.sessions().saveMessages(chatId, messages);
            }
            if (req.persist()) taint.prune(chatId, messages, false);
            Consumer<Usage> spent = u -> { deps.metrics().tokens(u); deps.sessions().addUsage(chatId, u); };
            RequestPrefix prefix = new RequestPrefix(req.pinned(), turnExtra, tools, req.client(),
                    !deps.config().parallelToolUse());
            ContextView view = deps.context().prepare(new ContextTurn(chatId, contextKey, messages, req.inherited(),
                    run.last, prefix, sink, cancelled, spent, req.persist()));
            if (view.logChanged() && req.persist()) deps.sessions().saveMessages(chatId, messages);

            ModelTurn.Reply reply = modelTurn.call(run, tools, prefix, view, spent);
            if (reply.done() != null) return reply.done();
            if (reply.retry()) {
                budget.refundTurn();
                continue;
            }
            ModelTurn.Step step = modelTurn.handle(run, reply.response());
            if (step.done() != null) return step.done();
            if (step.again()) continue;
            LoopResult stuck = toolRound.run(run, byName, refused, step.toolUses(), step.cut(), step.windowFull());
            if (stuck != null) return stuck;
        }
        sink.emit("warning", deps.mapper().createObjectNode()
                .put("message", "Stopped after " + budget.describe() + "."));
        return new LoopResult(run.finalText, Outcome.MAX_TURNS);
    }
}

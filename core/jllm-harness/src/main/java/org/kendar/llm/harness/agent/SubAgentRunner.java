package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.TemperatureModelClient;
import org.kendar.llm.harness.llm.ThinkingModelClient;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.plugin.OneShotModel;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Runs sub-agents (the fan-out tool) and agent tasks (flow steps) in their own message log. The transcript stays out of
 * the chat log and goes to the run audit trail; the token usage is billed to the chat.
 */
public class SubAgentRunner {
    private static final Logger log = LoggerFactory.getLogger(SubAgentRunner.class);

    private final AgentLoop.Deps deps;
    private final ToolOffer offer;
    private final TaintTracker taint;
    private final ModeNote modeNote;
    private final Function<LoopRequest, AgentLoop.LoopResult> loop;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, ActiveSubAgent> active = new ConcurrentHashMap<>();

    SubAgentRunner(AgentLoop.Deps deps, ToolOffer offer, TaintTracker taint, ModeNote modeNote,
                   Function<LoopRequest, AgentLoop.LoopResult> loop) {
        this.deps = deps;
        this.offer = offer;
        this.taint = taint;
        this.modeNote = modeNote;
        this.loop = loop;
    }

    /** The sub-agents running for the chat, oldest first. */
    public List<ActiveSubAgent> activeSubAgents(String chatId) {
        return active.values().stream().filter(a -> a.chatId().equals(chatId))
                .sorted(Comparator.comparingLong(ActiveSubAgent::startedAtMillis)).toList();
    }

    /**
     * Sub-agent used by the fan-out tool, in the mode the chat is in. Returns the final answer text. The transcript
     * stays out of the chat log and goes to the run audit trail; its token usage is billed to the chat.
     */
    public String run(SubAgentRequest request) {
        return runAgent(request, true, null);
    }

    /**
     * One task for an outside caller (a flow step) in the loop of the given agent type, with that agent's tools,
     * permissions and approvals. With {@code interactive} false a call that would need an approval is not asked
     * about and not run: the task fails with {@link OneShotModel.ApprovalRequiredException}.
     */
    public String runAgentTask(String chatId, String prompt, AgentDefinition agentType, boolean interactive,
                               Set<String> approved) {
        approved.forEach(t -> deps.approvals().grant(chatId, t));
        List<String> denied = new ArrayList<>();
        String answer;
        try {
            answer = runAgent(new SubAgentRequest(chatId, null, prompt, agentType, PermissionMode.DEFAULT, null,
                    Uuids.v7String()), interactive, denied);
        } catch (RuntimeException e) { // boundary: rethrown after reporting the denied tools
            if (!denied.isEmpty()) throw approvalRequired(denied);
            throw e;
        }
        if (!denied.isEmpty()) throw approvalRequired(denied);
        return answer;
    }

    private static OneShotModel.ApprovalRequiredException approvalRequired(List<String> denied) {
        List<String> tools = denied.stream().distinct().toList();
        return new OneShotModel.ApprovalRequiredException(
                "Approval needed for " + String.join(", ", tools) + " and no interactive approver is attached", tools);
    }

    private String runAgent(SubAgentRequest request, boolean interactive, List<String> denied) {
        String chatId = request.chatId();
        String toolUseId = request.toolUseId();
        String prompt = request.prompt();
        AgentDefinition agentType = request.agentType();
        PermissionMode parentMode = request.parentMode();
        AgentRun run = request.run();
        var config = deps.config();
        var sessions = deps.sessions();
        var sandbox = deps.sandbox();
        ArrayNode messages = deps.mapper().createArrayNode();
        int[] inherited = {0};
        if (agentType != null && agentType.inheritContext()) inherited[0] = copyParentContext(chatId, messages);
        boolean readOnly = agentType != null && agentType.readOnly();
        boolean stack = config.useContextStack();
        String roleNote = Prompts.load("subagent-suffix.md")
                + (readOnly ? "\n\n" + Prompts.load("subagent-readonly-suffix.md") : "")
                + (agentType == null || agentType.prompt().isBlank() ? "" : "\n\n" + agentType.prompt());
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        // context stack: the role text cannot go into the system prompt (it would change the shared prefix)
        user.putArray("content").addObject().put("type", "text")
                .put("text", stack ? roleNote + "\n\nTask:\n" + prompt : prompt);
        ModelClient client = agentType != null && agentType.model() != null ? agentType.model() : deps.model();
        if (agentType != null && agentType.temperature() != null) {
            client = new TemperatureModelClient(client, agentType.temperature());
        }
        if (agentType != null && agentType.thinking() != null) {
            client = new ThinkingModelClient(client, agentType.thinking());
        }

        PermissionMode mode = readOnly ? PermissionMode.PLAN : (parentMode == null ? PermissionMode.DEFAULT : parentMode);
        List<HarnessTool> tools;
        String extra;
        String pinned;
        StackFrame frame = null;
        if (stack) {
            // same pinned text, system prompt, mode note and tool list as the parent: the request continues its prefix
            ChatSessionData session = sessions.get(chatId);
            PermissionMode pm = parentMode == null ? PermissionMode.parse(session.mode()) : parentMode;
            String scratch = scratchId(session.agentId());
            ToolContext pctx = new ToolContext(chatId, sandbox.workDir(chatId), sandbox.scratchDir(chatId, scratch),
                    pm == PermissionMode.PLAN, null, pm, null, sandbox.files(chatId, scratch));
            tools = offer.chatTools(chatId, "");
            extra = deps.systemPrompt() + modeNote.of(pm, pctx);
            pinned = sessions.pinnedPrefix(session);
            Set<String> contextTools = new HashSet<>();
            offer.fixedTools().forEach(t -> contextTools.add(t.name()));
            frame = new StackFrame(extra, t -> {
                if (contextTools.contains(t.name())) return true;
                if (t == deps.subAgentTool() || t == deps.requestToolGroup()) return false;
                if (readOnly && t.kind() != ToolKind.READ_ONLY) return false;
                return agentType == null || agentType.tools().isEmpty() || agentType.tools().contains(t.name());
            });
        } else {
            // the registry holds neither task nor request_tool_group: sub-agents never see them
            tools = new ArrayList<>(deps.toolSelector().select(prompt,
                    deps.registry().all().stream().filter(t -> !readOnly || t.kind() == ToolKind.READ_ONLY)
                            .filter(t -> agentType == null || agentType.tools().isEmpty()
                                    || agentType.tools().contains(t.name()))
                            .toList()));
            tools.addAll(offer.fixedTools());
            // the user's tool filter, as for the main agent (the stack branch inherits the filtered list)
            UserPolicy policy = deps.identity().policyOf(chatId);
            tools.removeIf(t -> !policy.allows(t));
            tools.removeIf(t -> !sessions.chatAllowsTool(chatId, t.name()));
            offer.chatSkills(chatId, tools, policy);
            extra = deps.systemPrompt() + "\n\n" + roleNote;
            pinned = "chatId=" + chatId + "; subagent";
        }
        List<HarnessTool> subTools = tools;
        // a model failure inside the sub-agent must reach SubAgentTool as a failure, not an empty "ok" result
        String[] failure = {null};
        String[] warning = {null};
        Usage[] used = {Usage.ZERO};
        EventSink parentSink = run == null ? EventSink.NOOP : run.sink();
        BooleanSupplier cancelled = run == null ? () -> false : run.cancelled();
        EventSink errors = (type, data) -> {
            if ("approval_unavailable".equals(type)) {
                if (denied != null) denied.add(data.path("tool").asText("a tool"));
            } else if ("approval_request".equals(type) || "login_required".equals(type)
                    || ContentWarnings.EVENT.equals(type)) parentSink.emit(type, data);
            else if ("error".equals(type)) failure[0] = data.path("message").asText("sub-agent failed");
            else if ("warning".equals(type)) warning[0] = data.path("message").asText(null);
            else if ("usage".equals(type)) {
                used[0] = used[0].plus(new Usage(data.path("inputTokens").asLong(), data.path("outputTokens").asLong(),
                        data.path("cacheReadTokens").asLong(), data.path("cacheCreationTokens").asLong()));
            }
        };
        AgentLoop.LoopResult result = new AgentLoop.LoopResult("", AgentLoop.Outcome.FAILED);
        SubAgentIncompleteException incomplete = null;
        String agentName = agentType == null ? AgentCatalog.DEFAULT : agentType.name();
        String label = agentName + " #" + seq.incrementAndGet();
        HookResult entering = deps.hooks().run(HookContext.before(HookEvent.PRE_AGENT, chatId, agentName, prompt));
        if (entering.denied()) throw new IllegalStateException("Sub-agent blocked by a hook: " + entering.denyReason());
        active.put(label, new ActiveSubAgent(label, chatId, prompt, System.currentTimeMillis()));
        try {
            result = loop.apply(new LoopRequest(chatId, messages, () -> subTools, pinned, extra, mode, errors,
                    cancelled, false, interactive, agentName, client, inherited, label, null, frame));
            if (failure[0] == null) incomplete = incomplete(result, warning[0]);
        } catch (RuntimeException e) { // boundary: sub-agent boundary: remember the failure, rethrown unchanged
            if (failure[0] == null) failure[0] = String.valueOf(e.getMessage());
            throw e;
        } finally {
            active.remove(label);
            // untrusted output the sub-agent read reaches the parent through the result of its task call
            taint.passToParent(chatId, ToolPairingRepair.toolUseIds(messages), toolUseId);
            String answer = result.text();
            String outcome = failure[0] != null ? failure[0]
                    : incomplete != null ? answer + "\n[" + incomplete.getMessage() + "]" : answer;
            deps.hooks().run(HookContext.after(HookEvent.POST_AGENT, chatId, agentName, outcome,
                    failure[0] != null || incomplete != null));
            sessions.addUsage(chatId, used[0]);
            record(request.runId(), chatId, toolUseId, prompt, messages, outcome,
                    failure[0] != null ? "failed" : incomplete != null ? incomplete.status() : "ok", used[0]);
        }
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        if (incomplete != null) throw incomplete;
        return result.text();
    }

    /** The exception that reports a sub-agent run that ended without a final answer; null when it completed. */
    private static SubAgentIncompleteException incomplete(AgentLoop.LoopResult r, String warning) {
        String why = warning != null ? warning : switch (r.outcome()) {
            case TRUNCATED -> "The response was cut off";
            case REFUSED -> "The model declined to answer (refusal)";
            case MAX_TURNS -> "Stopped at the turn limit";
            case CANCELLED -> "Cancelled by the user";
            case STUCK -> "Stopped: the sub-agent kept repeating itself";
            case UNVERIFIED -> "Finished, but the stop check still fails";
            default -> "Ended without a final answer";
        };
        return switch (r.outcome()) {
            case COMPLETED -> null;
            case REFUSED -> new SubAgentIncompleteException("refused", r.text(), why);
            case STUCK -> new SubAgentIncompleteException("stuck", r.text(), why);
            case UNVERIFIED -> new SubAgentIncompleteException("unverified", r.text(), why);
            case CANCELLED -> new SubAgentIncompleteException("cancelled", r.text(), "Cancelled by the user");
            default -> new SubAgentIncompleteException("partial", r.text(), why);
        };
    }

    /** Directory-safe agent id: the scratch dir of an agent is named after it. */
    public static String scratchId(String agent) {
        String id = agent == null ? "" : agent.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        if (id.length() > 64) id = id.substring(0, 64);
        return id.isEmpty() ? "agent" : id;
    }

    private void record(String runId, String chatId, String toolUseId, String prompt, ArrayNode messages,
                        String answer, String status, Usage usage) {
        try {
            deps.subAgentRunLog().record(new SubAgentRunData(runId, chatId, toolUseId, prompt,
                    deps.sessions().toJsonLines(messages), answer, status, usage.inputTokens(),
                    usage.outputTokens(), usage.cacheReadTokens(), usage.cacheCreationTokens(), Instant.now()));
        } catch (RuntimeException e) { // boundary: recording a run is best effort
            log.warn("Cannot record sub-agent run for chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Copies the parent's persisted messages into {@code into}, without the assistant message whose tool calls are still
     * pending (the task call itself), and closes the copy with a short assistant message so the agent's own first
     * message can be a user turn. @return number of messages copied
     */
    private int copyParentContext(String chatId, ArrayNode into) {
        ChatSessionData session = deps.sessions().get(chatId);
        ArrayNode parent = deps.context().inheritedView(chatId, session.agentId(), deps.sessions().messages(session));
        int end = parent.size();
        if (end > 0 && "assistant".equals(parent.get(end - 1).path("role").asText())) end--;
        if (end == 0) return 0;
        for (int i = 0; i < end; i++) into.add(parent.get(i).deepCopy());
        into.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text")
                .put("text", "Delegating the next request to a sub-agent.");
        return into.size();
    }
}

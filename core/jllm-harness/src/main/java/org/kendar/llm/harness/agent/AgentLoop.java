package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.context.*;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.HookResult;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.llm.*;
import org.kendar.llm.harness.permission.*;
import org.kendar.llm.harness.safety.ToolOutputSanitizer;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxUnavailableException;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** ReAct loop: compact → model → (tool_use → gate/approval → execute → tool_result)* until a non-tool_use stop. */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /** Opens the user message that asks the model to continue a response cut off at max_tokens. */
    static final String CONTINUE_NOTE = "[harness: response cut off]";
    /** Cut-off text responses continued in a row before the run stops. */
    static final int MAX_CONTINUATIONS = 3;

    private final HarnessConfig config;
    private final ModelClient model;
    private final ContextStrategy context;
    private final ToolSelector toolSelector;
    private final PermissionGate gate;
    private final ApprovalService approvals;
    private final ChatSessionService sessions;
    private final SandboxExecutor sandbox;
    private final HarnessToolRegistry registry;
    private final SubAgentTool subAgentTool;
    private final RequestToolGroupTool requestToolGroup;
    private final SubAgentRunLog subAgentRunLog;
    private final ObjectMapper mapper;
    private final HarnessMetrics metrics;
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final String systemPrompt;
    private final Hooks hooks;
    private final ToolClassifier classifier;
    private final AtomicInteger subAgentSeq = new AtomicInteger();
    private final java.util.Map<String, ActiveSubAgent> activeSubAgents = new java.util.concurrent.ConcurrentHashMap<>();

    /** A sub-agent that is running now. */
    public record ActiveSubAgent(String label, String chatId, String prompt, long startedAtMillis) {
    }

    /** How a run of the loop ended. */
    enum Outcome { COMPLETED, TRUNCATED, REFUSED, MAX_TURNS, CANCELLED, FAILED }

    /** Final text of a run of the loop and how it ended. */
    record LoopResult(String text, Outcome outcome) {
    }

    /**
     * A sub-agent ended without a final answer (cut off, refused, out of turns, cancelled).
     *
     * @see #status() {@code partial}, {@code refused} or {@code cancelled}
     */
    public static final class SubAgentIncompleteException extends RuntimeException {
        private final String status;
        private final String partial;

        public SubAgentIncompleteException(String status, String partial, String warning) {
            super(warning);
            this.status = status;
            this.partial = partial == null ? "" : partial;
        }

        public String status() {
            return status;
        }

        /** What the sub-agent wrote before it stopped (may be empty). */
        public String partial() {
            return partial;
        }
    }

    /** The sub-agents running for the chat, oldest first. */
    public List<ActiveSubAgent> activeSubAgents(String chatId) {
        return activeSubAgents.values().stream().filter(a -> a.chatId().equals(chatId))
                .sorted(java.util.Comparator.comparingLong(ActiveSubAgent::startedAtMillis)).toList();
    }
    private final ToolOutputSanitizer sanitizer;
    private volatile org.kendar.llm.harness.tools.ToolInputValidator inputValidator =
            new org.kendar.llm.harness.tools.JsonSchemaToolInputValidator();
    /** Per-chat override of {@code output.validation}, set by {@code /validation}. */
    private final java.util.Map<String, org.kendar.llm.harness.tools.ValidationMode> validationModes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Replaces the validator of tool-call arguments. */
    public void inputValidator(org.kendar.llm.harness.tools.ToolInputValidator v) {
        this.inputValidator = java.util.Objects.requireNonNull(v);
    }

    /** The validation mode of the chat: its {@code /validation} override, else the configured one. */
    public org.kendar.llm.harness.tools.ValidationMode validationMode(String chatId) {
        org.kendar.llm.harness.tools.ValidationMode m = validationModes.get(chatId);
        return m != null ? m : org.kendar.llm.harness.tools.ValidationMode.parse(config.output().validation());
    }

    public void validationMode(String chatId, org.kendar.llm.harness.tools.ValidationMode mode) {
        validationModes.put(chatId, mode);
    }

    /**
     * Per-host approval for the web tools. A host the chat has granted passes (also after untrusted output was read:
     * a host not granted always asks, which is what stops data leaving). Not interactive: refused.
     */
    private org.kendar.llm.harness.tools.HostApprover hostApprover(String chatId, EventSink sink,
                                                                   BooleanSupplier cancelled, boolean interactive,
                                                                   String label) {
        return new org.kendar.llm.harness.tools.HostApprover() {
            @Override
            public boolean approve(String host, String url) {
                if (approvals.hostGranted(chatId, host)) return true;
                if (!interactive) return false;
                String h = org.kendar.llm.harness.tools.HostMatcher.normalize(host);
                ApprovalService.ApprovalDecision d = approvals.await(chatId, "host", "Allow web access to " + h,
                        url, null, a -> {
                            ObjectNode ev = mapper.valueToTree(a);
                            if (label != null) ev.put("agent", label);
                            ev.put("host", h);
                            sink.emit("approval_request", ev);
                        }, cancelled, () -> approvals.hostGranted(chatId, host));
                if (d.approved() && d.remember()) approvals.grantHost(chatId, host);
                metrics.approval("host", d.approved());
                return d.approved();
            }

            /** Per call, never remembered: a bad certificate is asked about every time. */
            @Override
            public boolean approveInsecure(String host, String url, String reason) {
                if (!interactive) return false;
                String h = org.kendar.llm.harness.tools.HostMatcher.normalize(host);
                ApprovalService.ApprovalDecision d = approvals.await(chatId, "insecure_cert",
                        "Invalid certificate for " + h + ": continue anyway?",
                        "The certificate of " + h + " is not valid:\n" + reason + "\n\nURL: " + url
                                + "\n\nAllowing sends this one request without checking who is on the other end.",
                        null, a -> {
                            ObjectNode ev = mapper.valueToTree(a);
                            if (label != null) ev.put("agent", label);
                            ev.put("host", h);
                            sink.emit("approval_request", ev);
                        }, cancelled);
                metrics.approval("insecure_cert", d.approved());
                return d.approved();
            }
        };
    }

    /** Built-in file tools (identity): free from a script. */
    private volatile java.util.Set<HarnessTool> builtinFileTools = java.util.Set.of();

    /** Who the calls of each chat are made for: per-user tools and approval. Default: {@code Identity.ANONYMOUS}. */
    private volatile org.kendar.llm.harness.auth.Identity identity = org.kendar.llm.harness.auth.Identity.ANONYMOUS;

    public AgentLoop identity(org.kendar.llm.harness.auth.Identity identity) {
        this.identity = identity;
        return this;
    }

    public AgentLoop builtinFileTools(java.util.Collection<HarnessTool> tools) {
        java.util.Set<HarnessTool> s = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        s.addAll(tools);
        this.builtinFileTools = s;
        return this;
    }

    private static final java.util.Set<String> SCRIPT_REFUSED = java.util.Set.of("run_js", "bash", "sub_agent",
            "request_tool_group");

    private boolean scriptRefused(HarnessTool t) {
        return SCRIPT_REFUSED.contains(t.name()) || "Agents".equals(t.group()) || t == subAgentTool
                || t == requestToolGroup;
    }

    /** The view of one running agent that a script uses; the tool list follows the agent turn by turn. */
    private final class Scope implements org.kendar.llm.harness.tools.RunScope {
        private final PermissionMode mode;
        private final EventSink sink;
        private final BooleanSupplier cancelled;
        private final boolean interactive;
        private final String label;
        volatile Map<String, HarnessTool> byName = Map.of();
        volatile String intent = "";

        Scope(PermissionMode mode, EventSink sink, BooleanSupplier cancelled, boolean interactive, String label) {
            this.mode = mode;
            this.sink = sink;
            this.cancelled = cancelled;
            this.interactive = interactive;
            this.label = label;
        }

        @Override
        public java.util.Collection<HarnessTool> tools() {
            return byName.values().stream().filter(t -> !scriptRefused(t)).toList();
        }

        @Override
        public ToolContext jail(ToolContext ctx) {
            if (!sandbox.sharedWorkDir()) return ctx;
            if (ctx.files() != null) {
                return ctx.withFiles(new org.kendar.llm.harness.sandbox.ScratchJailBackend(ctx.files()));
            }
            return ctx.withRoot(ctx.scratchDir());
        }

        @Override
        public String display(java.nio.file.Path path) {
            return sandbox.displayPath(path);
        }

        @Override
        public boolean free(String name) {
            HarnessTool t = byName.get(name);
            return t != null && builtinFileTools.contains(t);
        }

        @Override
        public String invoke(ToolContext ctx, String name, JsonNode input) {
            String parent = ctx.toolUseId() == null ? "run_js" : "run_js:" + ctx.toolUseId();
            ToolContext inner = ctx.withParent(parent);
            String id = (ctx.toolUseId() == null ? "js" : ctx.toolUseId()) + "/" + java.util.UUID.randomUUID();
            if (input == null || input.isNull()) input = mapper.createObjectNode();
            ObjectNode call = mapper.createObjectNode();
            call.put("id", id).put("name", name).put("parent", parent);
            call.set("input", input);
            sink.emit("tool_call", call);
            long start = System.nanoTime();
            String out = null;
            String error = null;
            try {
                HarnessTool tool = byName.get(name);
                // the list is the agent's of this turn; a tool disabled or removed since (/tools, /mcp) is refused now
                if (tool == null || scriptRefused(tool)
                        || !(registry.all().contains(tool) || context.tools().contains(tool))) {
                    throw new org.kendar.llm.harness.tools.ToolCatalog.ToolCallException(
                            "Unknown or unavailable tool '" + name + "'");
                }
                if (cancelled.getAsBoolean()) {
                    throw new org.kendar.llm.harness.tools.ToolCatalog.ToolCallException("cancelled by the user");
                }
                var check = checkInput(tool, input, inner);
                if (!check.valid()) {
                    throw new org.kendar.llm.harness.tools.ToolCatalog.ToolCallException("Invalid arguments for '"
                            + name + "': " + String.join("; ", check.errors()) + ". Expected input schema: "
                            + tool.inputSchema());
                }
                boolean free = builtinFileTools.contains(tool);
                Authz az = authorize(tool, name, check.input(), inner, mode, sink, cancelled, interactive, intent,
                        label, free);
                if (az.denial() != null) {
                    throw new org.kendar.llm.harness.tools.ToolCatalog.ToolCallException(az.denial());
                }
                ObjectNode r;
                try {
                    r = runTool(tool, az.input(), inner.withToolUseId(id), id, true);
                } catch (SandboxUnavailableException e) {
                    throw new org.kendar.llm.harness.tools.ToolCatalog.ToolCallException(e.getMessage());
                }
                out = r.path("content").asText("");
                boolean failed = r.path("is_error").asBoolean(false);
                hooks.run(HookContext.postTool(ctx.chatId(), name, az.input(), out, failed,
                        (System.nanoTime() - start) / 1_000_000L, parent));
                if (failed) {
                    throw new org.kendar.llm.harness.tools.ToolCatalog.ToolCallException(
                            out.startsWith("Error: ") ? out.substring(7) : out);
                }
                return out;
            } catch (org.kendar.llm.harness.tools.ToolCatalog.ToolCallException e) {
                error = e.getMessage();
                throw e;
            } catch (RuntimeException e) {
                // a hook or the permission check failed: the call still ends with an error result
                error = "Tool execution failed: " + e.getMessage();
                throw e;
            } finally {
                ObjectNode ev = mapper.createObjectNode();
                ev.put("id", id).put("name", name).put("parent", parent).put("isError", error != null);
                String text = error != null ? error : out == null ? "" : out;
                ev.put("size", text.length());
                int eventMax = config.output().eventMaxChars();
                ev.put("output", text.length() > eventMax ? text.substring(0, eventMax) + "…" : text);
                sink.emit("tool_result", ev);
                metrics.toolCall(name, error != null ? "error" : "ok",
                        java.time.Duration.ofNanos(System.nanoTime() - start));
            }
        }
    }

    /**
     * Per chat, the tool_use ids whose results carry untrusted output. The chat stays tainted while one of them is
     * still in its context. The set is stored with the chat ({@link ChatSessionService#taintedToolUses}), so a
     * restarted harness keeps gating. {@link #TAINT_RUN} stands for output that cannot be tied to a call of the log;
     * it lasts until the next user message.
     */
    private final java.util.Map<String, java.util.Set<String>> tainted = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String TAINT_RUN = "*";

    /** The tainted ids of the chat, loaded from the stored chat on first use. */
    private java.util.Set<String> taintSet(String chatId) {
        return tainted.computeIfAbsent(chatId, k -> {
            java.util.Set<String> s = java.util.concurrent.ConcurrentHashMap.newKeySet();
            try {
                s.addAll(sessions.taintedToolUses(k));
            } catch (RuntimeException e) {
                log.warn("Cannot load the taint state of chat {}: {}", k, e.toString());
            }
            return s;
        });
    }

    private void saveTaint(String chatId) {
        java.util.Set<String> ids = tainted.get(chatId);
        try {
            sessions.setTaintedToolUses(chatId, ids == null ? List.of() : new ArrayList<>(ids));
        } catch (RuntimeException e) {
            log.warn("Cannot store the taint state of chat {}: {}", chatId, e.toString());
        }
    }

    /** Drops the in-memory taint state of a chat (the chat was deleted). */
    public void forgetTaint(String chatId) {
        if (chatId != null) tainted.remove(chatId);
    }

    private boolean isTainted(String chatId) {
        return !taintSet(chatId).isEmpty();
    }

    private void taint(String chatId, String toolUseId) {
        // a call made by a script ("<run_js id>/<uuid>") is answered through the run_js result
        String id = toolUseId == null ? null : toolUseId.contains("/")
                ? toolUseId.substring(0, toolUseId.indexOf('/')) : toolUseId;
        if (id == null || id.isEmpty() || id.equals("js")) id = TAINT_RUN;
        if (taintSet(chatId).add(id)) saveTaint(chatId);
    }

    /** Drops the tainted calls whose result is no longer in the log, or was cleared by compaction. */
    private void pruneTaint(String chatId, ArrayNode messages, boolean newUserMessage) {
        java.util.Set<String> ids = taintSet(chatId);
        if (ids.isEmpty()) return;
        boolean changed = newUserMessage && ids.remove(TAINT_RUN);
        java.util.Set<String> inContext = new java.util.HashSet<>();
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_result".equals(b.path("type").asText())
                        && !b.path("content").asText("").startsWith("[tool result cleared")) {
                    inContext.add(b.path("tool_use_id").asText());
                }
            }
        }
        changed |= ids.removeIf(id -> !TAINT_RUN.equals(id) && !inContext.contains(id));
        if (changed) saveTaint(chatId);
    }

    /**
     * Gives every tool_use of an assistant message that is followed by another message an (error) tool_result in the
     * message right after it, as the APIs require. The last message is left alone (its calls may still run).
     *
     * @return whether the log changed
     */
    boolean repairToolPairing(ArrayNode messages) {
        boolean changed = false;
        for (int i = 0; i < messages.size() - 1; i++) {
            JsonNode m = messages.get(i);
            if (!"assistant".equals(m.path("role").asText())) continue;
            List<String> uses = new ArrayList<>();
            for (JsonNode b : m.path("content")) {
                if ("tool_use".equals(b.path("type").asText())) uses.add(b.path("id").asText());
            }
            if (uses.isEmpty()) continue;
            JsonNode next = messages.get(i + 1);
            java.util.Set<String> answered = new java.util.HashSet<>();
            if ("user".equals(next.path("role").asText())) {
                for (JsonNode b : next.path("content")) {
                    if ("tool_result".equals(b.path("type").asText())) answered.add(b.path("tool_use_id").asText());
                }
            }
            List<String> missing = uses.stream().filter(id -> !answered.contains(id)).toList();
            if (missing.isEmpty()) continue;
            ArrayNode fixed = mapper.createArrayNode();
            for (String id : missing) fixed.add(toolResult(id, "Not executed: the call was interrupted.", true));
            if ("user".equals(next.path("role").asText())) {
                // tool results go first in the user message
                JsonNode old = next.path("content");
                if (old.isArray()) fixed.addAll((ArrayNode) old);
                else if (old.isTextual()) fixed.addObject().put("type", "text").put("text", old.asText());
                ((ObjectNode) next).set("content", fixed);
            } else {
                ObjectNode user = mapper.createObjectNode();
                user.put("role", "user");
                user.set("content", fixed);
                messages.insert(i + 1, user);
            }
            log.warn("Answered {} tool call(s) left without a result", missing.size());
            changed = true;
        }
        return changed;
    }

    /** The tool_use ids of a message log. */
    private static java.util.Set<String> toolUseIds(ArrayNode messages) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonNode m : messages) {
            for (JsonNode b : m.path("content")) {
                if ("tool_use".equals(b.path("type").asText())) ids.add(b.path("id").asText());
            }
        }
        return ids;
    }

    /**
     * @param requestToolGroup on-demand tool groups; null disables them
     * @param subAgentRunLog   audit trail of sub-agent runs
     * @param systemPrompt     static system text sent with every request
     */
    public AgentLoop(HarnessConfig config, ModelClient model, ContextStrategy context,
                     ToolSelector toolSelector, PermissionGate gate, ApprovalService approvals,
                     ChatSessionService sessions, SandboxExecutor sandbox, HarnessToolRegistry registry,
                     SubAgentTool subAgentTool, RequestToolGroupTool requestToolGroup,
                     SubAgentRunLog subAgentRunLog, ObjectMapper mapper, HarnessMetrics metrics,
                     String systemPrompt) {
        this(config, model, context, toolSelector, gate, approvals, sessions, sandbox, registry,
                subAgentTool, requestToolGroup, subAgentRunLog, mapper, metrics, systemPrompt, Hooks.NONE);
    }

    /** @param hooks callbacks around prompts and tool calls */
    public AgentLoop(HarnessConfig config, ModelClient model, ContextStrategy context,
                     ToolSelector toolSelector, PermissionGate gate, ApprovalService approvals,
                     ChatSessionService sessions, SandboxExecutor sandbox, HarnessToolRegistry registry,
                     SubAgentTool subAgentTool, RequestToolGroupTool requestToolGroup,
                     SubAgentRunLog subAgentRunLog, ObjectMapper mapper, HarnessMetrics metrics,
                     String systemPrompt, Hooks hooks) {
        this(config, model, context, toolSelector, gate, approvals, sessions, sandbox, registry,
                subAgentTool, requestToolGroup, subAgentRunLog, mapper, metrics, systemPrompt, hooks, null);
    }

    /** @param classifier in auto mode decides whether a tool call may run unasked; null = auto mode never asks */
    public AgentLoop(HarnessConfig config, ModelClient model, ContextStrategy context,
                     ToolSelector toolSelector, PermissionGate gate, ApprovalService approvals,
                     ChatSessionService sessions, SandboxExecutor sandbox, HarnessToolRegistry registry,
                     SubAgentTool subAgentTool, RequestToolGroupTool requestToolGroup,
                     SubAgentRunLog subAgentRunLog, ObjectMapper mapper, HarnessMetrics metrics,
                     String systemPrompt, Hooks hooks, ToolClassifier classifier) {
        this.classifier = classifier;
        this.hooks = hooks;
        this.metrics = metrics;
        this.config = config;
        this.model = model;
        this.context = context;
        this.toolSelector = toolSelector;
        this.gate = gate;
        this.approvals = approvals;
        this.sessions = sessions;
        this.sandbox = sandbox;
        this.registry = registry;
        this.subAgentTool = subAgentTool;
        this.requestToolGroup = requestToolGroup;
        this.subAgentRunLog = subAgentRunLog;
        this.mapper = mapper;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.sanitizer = new ToolOutputSanitizer(ToolOutputSanitizer.Mode.parse(config.output().sanitize()),
                config.output().extraPatterns());
    }

    /** Runs one user turn of a persisted chat to completion. */
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

    public void run(String chatId, String userText, EventSink sink, BooleanSupplier cancelled) {
        run(chatId, userText, sink, cancelled, null);
    }

    public void run(String chatId, String userText, EventSink sink, BooleanSupplier cancelled, Inbox inbox) {
        HookResult prompt = hooks.run(HookContext.prompt(chatId, userText));
        if (prompt.denied()) {
            sink.emit("warning", mapper.createObjectNode()
                    .put("message", "Prompt blocked by a hook: " + prompt.denyReason()));
            return;
        }
        HookResult entering = hooks.run(HookContext.before(HookEvent.PRE_AGENT, chatId, HookContext.MAIN, userText));
        if (entering.denied()) {
            sink.emit("warning", mapper.createObjectNode()
                    .put("message", "Agent blocked by a hook: " + entering.denyReason()));
            return;
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
        pruneTaint(chatId, messages, true);

        String answer = "";
        boolean failed = true;
        try {
            answer = loop(chatId, messages, () -> chatTools(chatId, userText), sessions.pinnedPrefix(session),
                systemPrompt, PermissionMode.parse(session.mode()), sink, cancelled, true, true, HookContext.MAIN,
                    model, new int[]{0}, null, inbox).text();
            failed = false;
        } finally {
            hooks.run(HookContext.after(HookEvent.POST_AGENT, chatId, HookContext.MAIN, answer, failed));
        }
        if (PermissionMode.parse(sessions.get(chatId).mode()) == PermissionMode.PLAN && !answer.isBlank()) {
            sink.emit("plan_ready", mapper.createObjectNode().put("message",
                    "Plan ready. Type /edit to let the agent change files, or /auto to run without asking."));
        }
        hooks.run(HookContext.stop(chatId, answer));
        if (noisePruner != null) noisePruner.afterTurn(chatId);
    }

    private org.kendar.llm.harness.context.noise.NoiseCommandPruner noisePruner;

    /** Removes no-information shell commands from the context after each main-agent run; null = off. */
    public AgentLoop noisePruner(org.kendar.llm.harness.context.noise.NoiseCommandPruner pruner) {
        this.noisePruner = pruner;
        return this;
    }

    /**
     * The tool set is chosen once, on the chat's first message, and stored; it only grows through
     * request_tool_group. Keeping it stable keeps the prompt prefix (and the model's KV cache) intact.
     */
    private List<HarnessTool> chatTools(String chatId, String userText) {
        if (!sessions.hasToolGroups(chatId)) {
            sessions.setToolGroups(chatId, toolSelector.selectGroups(userText, registry.all()));
        }
        List<HarnessTool> tools = new ArrayList<>(toolSelector.forGroups(sessions.toolGroups(chatId), registry.all()));
        tools.addAll(context.tools());
        if (requestToolGroup != null && !requestToolGroup.missing(chatId).isEmpty()) tools.add(requestToolGroup);
        tools.add(subAgentTool);
        // the user's tool filter: applied to every tool offered to the model, including the ones added above
        org.kendar.llm.harness.auth.UserPolicy policy = identity.policyOf(chatId);
        tools.removeIf(t -> !policy.allows(t));
        // the chat's own tool set (tools switched off for this chat only)
        tools.removeIf(t -> !sessions.chatAllowsTool(chatId, t.name()));
        chatSkills(chatId, tools, policy);
        return tools;
    }

    /**
     * Restricts the skill tool to the skills of the chat's own set and of the user's policy, adds the user's own
     * skills; drops it when none is left.
     */
    private void chatSkills(String chatId, List<HarnessTool> tools, org.kendar.llm.harness.auth.UserPolicy policy) {
        for (int i = tools.size() - 1; i >= 0; i--) {
            if (tools.get(i) instanceof org.kendar.llm.harness.skills.SkillTool st) {
                var view = st.forChat(skill -> policy.keepsSkill(skill) && sessions.chatAllowsSkill(chatId, skill),
                        policy.skills());
                if (view.offered().isEmpty() && (st.catalogEmpty() || !st.offered().isEmpty())) tools.remove(i);
                else tools.set(i, view);
            }
        }
    }

    /** Manual compaction of a persisted chat (the /compact command). */
    public CompactResult compact(String chatId, String focus) {
        ArrayNode messages = sessions.messages(sessions.get(chatId));
        ChatSessionData session = sessions.get(chatId);
        PermissionMode mode = PermissionMode.parse(session.mode());
        RequestPrefix prefix = null;
        if (sessions.hasToolGroups(chatId)) {
            String scratch = scratchId(session.agentId());
            ToolContext t = new ToolContext(chatId, sandbox.workDir(chatId), sandbox.scratchDir(chatId, scratch),
                    mode == PermissionMode.PLAN, null, mode, null, sandbox.files(chatId, scratch));
            String extra = systemPrompt + modeNote(mode, t);
            prefix = new RequestPrefix(sessions.pinnedPrefix(session), extra, chatTools(chatId, ""), model,
                    !config.parallelToolUse());
        }
        CompactResult r = context.compactNow(new ContextTurn(chatId, session.agentId(), messages, null, null, prefix,
                null, null, u -> { metrics.tokens(u); sessions.addUsage(chatId, u); }, true), focus);
        if (r.changed()) sessions.saveMessages(chatId, messages);
        return r;
    }

    /**
     * Sub-agent used by the fan-out tool, in the mode the chat is in. Returns the final answer text. The transcript
     * stays out of the chat log and goes to the run audit trail; its token usage is billed to the chat.
     *
     * @param toolUseId id of the parent "task" tool call, for correlation; may be null
     */
    public String runSubAgent(String chatId, String toolUseId, String prompt) {
        return runSubAgent(chatId, toolUseId, prompt, null);
    }

    /** As above, as an agent of the given type: its tool subset and its extra instructions. */
    public String runSubAgent(String chatId, String toolUseId, String prompt, AgentDefinition agentType) {
        return runSubAgent(chatId, toolUseId, prompt, agentType, PermissionMode.parse(sessions.get(chatId).mode()),
                null);
    }

    /**
     * @param parentMode mode of the caller when it made the call: the sub-agent never runs looser than that. A
     *                   read-only type runs as PLAN with the read-only tools only, whatever the mode
     * @param run        stream and cancel flag of the caller: approvals are forwarded to it; null = none
     */
    public String runSubAgent(String chatId, String toolUseId, String prompt, AgentDefinition agentType,
                              PermissionMode parentMode, AgentRun run) {
        return runSubAgent(chatId, toolUseId, prompt, agentType, parentMode, run, Uuids.v7String());
    }

    /** @param runId id the run is recorded under in the {@link SubAgentRunLog} */
    public String runSubAgent(String chatId, String toolUseId, String prompt, AgentDefinition agentType,
                              PermissionMode parentMode, AgentRun run, String runId) {
        ArrayNode messages = mapper.createArrayNode();
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
        ModelClient client = agentType != null && agentType.model() != null ? agentType.model() : model;
        if (agentType != null && agentType.temperature() != null) {
            client = new org.kendar.llm.harness.llm.TemperatureModelClient(client, agentType.temperature());
        }
        if (agentType != null && agentType.thinking() != null) {
            client = new org.kendar.llm.harness.llm.ThinkingModelClient(client, agentType.thinking());
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
            tools = chatTools(chatId, "");
            extra = systemPrompt + modeNote(pm, pctx);
            pinned = sessions.pinnedPrefix(session);
            java.util.Set<String> contextTools = new java.util.HashSet<>();
            context.tools().forEach(t -> contextTools.add(t.name()));
            frame = new StackFrame(extra, t -> {
                if (contextTools.contains(t.name())) return true;
                if (t == subAgentTool || t == requestToolGroup) return false;
                if (readOnly && t.kind() != ToolKind.READ_ONLY) return false;
                return agentType == null || agentType.tools().isEmpty() || agentType.tools().contains(t.name());
            });
        } else {
            // the registry holds neither task nor request_tool_group: sub-agents never see them
            tools = new ArrayList<>(toolSelector.select(prompt,
                    registry.all().stream().filter(t -> !readOnly || t.kind() == ToolKind.READ_ONLY)
                            .filter(t -> agentType == null || agentType.tools().isEmpty()
                                    || agentType.tools().contains(t.name()))
                            .toList()));
            tools.addAll(context.tools());
            // the user's tool filter, as for the main agent (the stack branch inherits the filtered list)
            org.kendar.llm.harness.auth.UserPolicy policy = identity.policyOf(chatId);
            tools.removeIf(t -> !policy.allows(t));
            tools.removeIf(t -> !sessions.chatAllowsTool(chatId, t.name()));
            chatSkills(chatId, tools, policy);
            extra = systemPrompt + "\n\n" + roleNote;
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
            if ("approval_request".equals(type)) parentSink.emit(type, data);
            else if ("error".equals(type)) failure[0] = data.path("message").asText("sub-agent failed");
            else if ("warning".equals(type)) warning[0] = data.path("message").asText(null);
            else if ("usage".equals(type)) {
                used[0] = used[0].plus(new Usage(data.path("inputTokens").asLong(), data.path("outputTokens").asLong(),
                        data.path("cacheReadTokens").asLong(), data.path("cacheCreationTokens").asLong()));
            }
        };
        LoopResult result = new LoopResult("", Outcome.FAILED);
        SubAgentIncompleteException incomplete = null;
        String agentName = agentType == null ? AgentCatalog.DEFAULT : agentType.name();
        String label = agentName + " #" + subAgentSeq.incrementAndGet();
        HookResult entering = hooks.run(HookContext.before(HookEvent.PRE_AGENT, chatId, agentName, prompt));
        if (entering.denied()) throw new IllegalStateException("Sub-agent blocked by a hook: " + entering.denyReason());
        activeSubAgents.put(label, new ActiveSubAgent(label, chatId, prompt, System.currentTimeMillis()));
        try {
            result = loop(chatId, messages, () -> subTools, pinned, extra,
                    mode, errors, cancelled, false, true, agentName, client, inherited, label, null, frame);
            if (failure[0] == null) incomplete = incomplete(result, warning[0]);
        } catch (RuntimeException e) {
            if (failure[0] == null) failure[0] = String.valueOf(e.getMessage());
            throw e;
        } finally {
            activeSubAgents.remove(label);
            // untrusted output the sub-agent read reaches the parent through the result of its task call
            java.util.Set<String> ids = taintSet(chatId);
            java.util.Set<String> own = toolUseIds(messages);
            if (ids.removeIf(own::contains)) {
                taint(chatId, toolUseId);
                saveTaint(chatId); // taint() saves only when the parent id is new
            }
            String answer = result.text();
            String outcome = failure[0] != null ? failure[0]
                    : incomplete != null ? answer + "\n[" + incomplete.getMessage() + "]" : answer;
            hooks.run(HookContext.after(HookEvent.POST_AGENT, chatId, agentName, outcome,
                    failure[0] != null || incomplete != null));
            sessions.addUsage(chatId, used[0]);
            recordSubAgent(runId, chatId, toolUseId, prompt, messages, outcome,
                    failure[0] != null ? "failed" : incomplete != null ? incomplete.status() : "ok", used[0]);
        }
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        if (incomplete != null) throw incomplete;
        return result.text();
    }

    /** The exception that reports a sub-agent run that ended without a final answer; null when it completed. */
    private static SubAgentIncompleteException incomplete(LoopResult r, String warning) {
        String why = warning != null ? warning : switch (r.outcome()) {
            case TRUNCATED -> "The response was cut off";
            case REFUSED -> "The model declined to answer (refusal)";
            case MAX_TURNS -> "Stopped at the turn limit";
            case CANCELLED -> "Cancelled by the user";
            default -> "Ended without a final answer";
        };
        return switch (r.outcome()) {
            case COMPLETED -> null;
            case REFUSED -> new SubAgentIncompleteException("refused", r.text(), why);
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

    /**
     * The shell description, only when this chat's user may run the shell tool: a user without it (e.g. run_js only)
     * must not be told about cmd.exe or bash, or the model claims and tries a shell it does not have.
     */
    private String shellNote(String chatId) {
        HarnessTool shell = registry.get("bash");
        boolean usable = shell != null && identity.policyOf(chatId).allows(shell)
                && sessions.chatAllowsTool(chatId, "bash");
        if (usable) return sandbox.shellEnvironment();
        return "OS: " + System.getProperty("os.name") + ". There is no shell tool in this chat: do not claim or "
                + "suggest shell commands.";
    }

    /** Date, OS and shell, directories and permission mode: after the cached system prefix. */
    private String modeNote(PermissionMode mode, ToolContext ctx) {
        String scratch = "\n\nToday's date: " + LocalDate.now()
                + "\n" + shellNote(ctx.chatId())
                + "\nWorking directory (project files go here, relative paths resolve here): "
                + sandbox.displayPath(ctx.workDir())
                + "\nScratch directory (only for throwaway files, never for deliverables): "
                + sandbox.displayPath(ctx.scratchDir());
        return switch (mode) {
            case PLAN -> "\n\nMode: PLAN. Investigate and write a plan; do not change the working directory ("
                    + sandbox.displayPath(ctx.workDir()) + "), treat it as read-only. Shell commands run in the scratch directory." + scratch;
            case EDIT -> "\n\nMode: EDIT. File edits are applied without asking; shell commands may ask." + scratch;
            case AUTO -> "\n\nMode: AUTO. Tool calls run without asking." + scratch;
            default -> "\n\nMode: DEFAULT. Tool calls that change things ask the user first." + scratch;
        };
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
            if (t != null && !t.startsWith(CONTINUE_NOTE)) return t;
        }
        return "";
    }

    private static String responseText(ModelResponse resp) {
        StringBuilder text = new StringBuilder();
        for (JsonNode b : resp.content()) {
            if ("text".equals(b.path("type").asText())) text.append(b.path("text").asText());
        }
        return text.toString();
    }

    private void recordSubAgent(String runId, String chatId, String toolUseId, String prompt, ArrayNode messages, String answer,
                                String status, Usage usage) {
        try {
            subAgentRunLog.record(new SubAgentRunData(runId, chatId, toolUseId, prompt,
                    sessions.toJsonLines(messages), answer, status, usage.inputTokens(),
                    usage.outputTokens(), usage.cacheReadTokens(), usage.cacheCreationTokens(), Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Cannot record sub-agent run for chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Copies the parent's persisted messages into {@code into}, without the assistant message whose tool calls are still
     * pending (the task call itself), and closes the copy with a short assistant message so the agent's own first
     * message can be a user turn. @return number of messages copied
     */
    private int copyParentContext(String chatId, ArrayNode into) {
        ChatSessionData session = sessions.get(chatId);
        ArrayNode parent = context.inheritedView(chatId, session.agentId(), sessions.messages(session));
        int end = parent.size();
        if (end > 0 && "assistant".equals(parent.get(end - 1).path("role").asText())) end--;
        if (end == 0) return 0;
        for (int i = 0; i < end; i++) into.add(parent.get(i).deepCopy());
        into.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text")
                .put("text", "Delegating the next request to a sub-agent.");
        return into.size();
    }

    private LoopResult loop(String chatId, ArrayNode messages, Supplier<List<HarnessTool>> toolSource, String pinned,
                        String extra, PermissionMode mode, EventSink sink, BooleanSupplier cancelled,
                        boolean persist, boolean interactive, String agent) {
        return loop(chatId, messages, toolSource, pinned, extra, mode, sink, cancelled, persist, interactive, agent,
                model, new int[]{0}, null);
    }

    /**
     * @param toolSource re-read every turn, so tool groups enabled mid-run apply to the next model call
     * @param client     model of this agent
     * @param inherited  [0] = leading messages that are a copy of the parent's context (0 for the main agent)
     * @param label      names a sub-agent in its approval requests; null for the main agent
     */
    private LoopResult loop(String chatId, ArrayNode messages, Supplier<List<HarnessTool>> toolSource, String pinned,
                        String extra, PermissionMode mode, EventSink sink, BooleanSupplier cancelled,
                        boolean persist, boolean interactive, String agent, ModelClient client, int[] inherited,
                        String label) {
        return loop(chatId, messages, toolSource, pinned, extra, mode, sink, cancelled, persist, interactive, agent,
                client, inherited, label, null);
    }

    private LoopResult loop(String chatId, ArrayNode messages, Supplier<List<HarnessTool>> toolSource, String pinned,
                        String extra, PermissionMode mode, EventSink sink, BooleanSupplier cancelled,
                        boolean persist, boolean interactive, String agent, ModelClient client, int[] inherited,
                        String label, Inbox inbox) {
        return loop(chatId, messages, toolSource, pinned, extra, mode, sink, cancelled, persist, interactive, agent,
                client, inherited, label, inbox, null);
    }

    /** Request prefix of a sub-agent that continues its parent's (context stack): fixed system text, tool filter. */
    private record StackFrame(String turnExtra, java.util.function.Predicate<HarnessTool> allowed) {
    }

    private LoopResult loop(String chatId, ArrayNode messages, Supplier<List<HarnessTool>> toolSource, String pinned,
                        String extra, PermissionMode mode, EventSink sink, BooleanSupplier cancelled,
                        boolean persist, boolean interactive, String agent, ModelClient client, int[] inherited,
                        String label, Inbox inbox, StackFrame frame) {
        String scratchId = persist ? scratchId(sessions.get(chatId).agentId())
                : scratchId(agent.length() > 26 ? agent.substring(0, 26) : agent) + "-" + Uuids.v7String();
        // the main agent's context state lives as long as the chat; a sub-agent's only for this run
        String contextKey = persist ? sessions.get(chatId).agentId() : scratchId;
        try {
            return runLoop(chatId, messages, toolSource, pinned, extra, mode, sink, cancelled, persist, interactive,
                    agent, client, inherited, label, scratchId, contextKey, inbox, frame);
        } finally {
            if (!persist) context.release(chatId, contextKey);
        }
    }

    private LoopResult runLoop(String chatId, ArrayNode messages, Supplier<List<HarnessTool>> toolSource, String pinned,
                           String extra, PermissionMode mode, EventSink sink, BooleanSupplier cancelled,
                           boolean persist, boolean interactive, String agent, ModelClient client, int[] inherited,
                           String label, String scratchId, String contextKey, Inbox inbox, StackFrame frame) {
        Scope scope = new Scope(mode, sink, cancelled, interactive, label);
        ToolContext tctx = new ToolContext(chatId, sandbox.workDir(chatId), sandbox.scratchDir(chatId, scratchId),
                mode == PermissionMode.PLAN, null, mode, new AgentRun(sink, cancelled, contextKey),
                sandbox.files(chatId, scratchId)).withScope(scope)
                .withHosts(hostApprover(chatId, sink, cancelled, interactive, label));
        // the mode goes after the cached system prefix, so switching modes does not invalidate the prompt cache
        String turnExtra = frame != null ? frame.turnExtra() : extra + modeNote(mode, tctx);
        boolean overflowRetried = false;
        boolean truncationRetried = false;
        int continuations = 0;
        String carried = ""; // text of the cut-off responses the current one continues
        CancelToken token = cancelled instanceof CancelToken ct ? ct : CancelToken.NONE;
        Usage last = null;
        String finalText = "";

        boolean turnHadThinking = false; // a thinking block of this turn is in the log
        boolean thinkingOff = false;     // history was rewritten mid-turn: their signatures are void, stop thinking
        int[] turnLimit = {config.maxTurns()};
        for (int turn = 0; turn < turnLimit[0]
                || continueAfterLimit(turnLimit, turn, chatId, interactive, sink, cancelled, label); turn++) {
            if (cancelled.getAsBoolean()) {
                sink.emit("cancelled", mapper.createObjectNode());
                return new LoopResult(finalText, Outcome.CANCELLED);
            }
            List<HarnessTool> tools = toolSource.get();
            Map<String, HarnessTool> byName = new LinkedHashMap<>();
            java.util.Set<String> refused = new java.util.LinkedHashSet<>();
            tools.forEach(t -> {
                if (frame == null || frame.allowed().test(t)) byName.put(t.name(), t);
                else refused.add(t.name());
            });
            scope.byName = byName;

            // a tool_use without its tool_result (an older log, a crash) makes every request fail: answer it now
            if (repairToolPairing(messages) && persist) sessions.saveMessages(chatId, messages);
            if (persist) pruneTaint(chatId, messages, false);
            java.util.function.Consumer<Usage> spent = u -> { metrics.tokens(u); sessions.addUsage(chatId, u); };
            RequestPrefix prefix = new RequestPrefix(pinned, turnExtra, tools, client, !config.parallelToolUse());
            ContextView view = context.prepare(new ContextTurn(chatId, contextKey, messages, inherited, last, prefix,
                    sink, cancelled, spent, persist));
            if (view.logChanged() && persist) sessions.saveMessages(chatId, messages);
            if (view.changed()) {
            }
            if (turnHadThinking && !thinkingOff && !org.kendar.llm.harness.context.compaction.ContextManager
                    .currentTurnHasThinking(messages)) {
                thinkingOff = true;
                sink.emit("warning", mapper.createObjectNode().put("message",
                        "Context compacted mid-turn: thinking is off for the rest of this turn"));
            }

            metrics.turn();
            ModelResponse resp;
            long modelStart = System.nanoTime();
            HookResult calling = hooks.run(HookContext.before(HookEvent.PRE_LLM, chatId, agent, null));
            if (calling.denied()) {
                // nothing was appended for this turn, so the message log stays consistent
                sink.emit("error", mapper.createObjectNode()
                        .put("message", "Model call blocked by a hook: " + calling.denyReason()));
                return new LoopResult(finalText, Outcome.FAILED);
            }
            try {
                ModelRequest request = new ModelRequest(pinned, turnExtra, view.messages(), tools, config.maxTokens(),
                        null, !config.parallelToolUse());
                if (thinkingOff) request = request.withThinkingLevel(org.kendar.llm.harness.llm.ThinkingLevel.MINIMAL);
                token.onRetry((status, attempt, max, delayMs) -> sink.emit("retry", mapper.createObjectNode()
                        .put("status", status).put("attempt", attempt).put("max", max).put("delayMs", delayMs)
                        .put("message", "Model call failed" + (status > 0 ? " (HTTP " + status + ")" : "")
                                + ", retry " + attempt + "/" + max + " in " + Math.max(1, delayMs / 1000) + " s")));
                resp = client.complete(request, token);
                metrics.modelCall(java.time.Duration.ofNanos(System.nanoTime() - modelStart), "ok");
                hooks.run(HookContext.afterLlm(chatId, agent, responseText(resp), false,
                        (System.nanoTime() - modelStart) / 1_000_000L, resp.usage()));
            } catch (RuntimeException e) {
                hooks.run(HookContext.afterLlm(chatId, agent, String.valueOf(e.getMessage()), true,
                        (System.nanoTime() - modelStart) / 1_000_000L, null));
                if (e instanceof ModelCancelledException || cancelled.getAsBoolean()) {
                    // nothing was appended for this turn, so the message log stays consistent
                    metrics.modelCall(java.time.Duration.ofNanos(System.nanoTime() - modelStart), "cancelled");
                    sink.emit("cancelled", mapper.createObjectNode());
                    return new LoopResult(finalText, Outcome.CANCELLED);
                }
                if (!overflowRetried && ModelException.isContextOverflow(e)) {
                    // rejected as too long: let the strategy shrink the request once; a second rejection is an error
                    overflowRetried = true;
                    ContextView retry = context.onOverflow(new ContextTurn(chatId, contextKey, messages, inherited,
                            null, prefix, sink, cancelled, spent, persist));
                    if (retry != null) {
                        if (retry.logChanged() && persist) sessions.saveMessages(chatId, messages);
                        last = null;
                        turn--;
                        continue;
                    }
                }
                metrics.modelCall(java.time.Duration.ofNanos(System.nanoTime() - modelStart), "error");
                log.warn("Model call failed: {}", e.getMessage());
                sink.emit("error", mapper.createObjectNode().put("message", String.valueOf(e.getMessage())));
                return new LoopResult(finalText, Outcome.FAILED);
            }
            overflowRetried = false;
            last = resp.usage();
            metrics.tokens(resp.usage());
            if (persist) sessions.addUsage(chatId, resp.usage());
            sink.emit("usage", mapper.valueToTree(resp.usage()));

            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.set("content", resp.content());

            StringBuilder text = new StringBuilder();
            List<JsonNode> toolUses = new ArrayList<>();
            for (JsonNode b : resp.content()) {
                String type = b.path("type").asText();
                if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                    turnHadThinking = true;
                } else if ("text".equals(type)) {
                    text.append(b.path("text").asText());
                    sink.emit("text", mapper.createObjectNode().put("text", b.path("text").asText()));
                } else if ("tool_use".equals(type)) {
                    toolUses.add(b);
                }
            }
            if (!text.isEmpty()) finalText = carried + text;
            carried = "";
            if (persist) sessions.saveMessages(chatId, messages);

            String stop = resp.stopReason();
            if ("pause_turn".equals(stop)) {
                continue; // server asked us to resume with the assistant content as-is
            }
            JsonNode cut = null;
            boolean windowFull = false;
            if ("max_tokens".equals(stop)) {
                // only the last block can be cut off: the tool calls before it are complete and still run
                JsonNode lastBlock = resp.content().isEmpty() ? null : resp.content().get(resp.content().size() - 1);
                if (lastBlock != null && "tool_use".equals(lastBlock.path("type").asText())) cut = lastBlock;
                int complete = toolUses.size() - (cut == null ? 0 : 1);
                windowFull = stoppedByWindow(resp.usage());
                // a response that is only a cut-off call is retried once; twice in a row stops the run
                boolean resume = !toolUses.isEmpty() && (complete > 0 || !truncationRetried);
                // cut-off text is continued; a full window would only cut the continuation again
                boolean continueText = toolUses.isEmpty() && !windowFull && continuations < MAX_CONTINUATIONS;
                sink.emit("warning", mapper.createObjectNode().put("message", (windowFull
                        ? "The response stopped at " + resp.usage().outputTokens() + " output tokens, below max_tokens ("
                        + config.maxTokens() + "): the server's context window is full."
                        : "The response hit max_tokens (" + config.maxTokens() + ") and was cut off.")
                        + (cut != null ? " Its last tool call (" + cut.path("name").asText()
                        + ") is incomplete and was not run." : "")
                        + (complete > 0 ? " Running the " + complete + " complete tool call(s)." : "")
                        + (continueText ? " Asking the model to continue." : "")));
                if (toolUses.isEmpty()) {
                    if (!continueText) return new LoopResult(finalText, Outcome.TRUNCATED);
                    continuations++;
                    carried = finalText;
                    ObjectNode more = messages.addObject();
                    more.put("role", "user");
                    more.putArray("content").addObject().put("type", "text").put("text", CONTINUE_NOTE
                            + " Your previous response was cut off at the output limit of " + config.maxTokens()
                            + " tokens. Continue exactly where it stopped, without repeating what you already wrote.");
                    if (persist) sessions.saveMessages(chatId, messages);
                    continue;
                }
                continuations = 0;
                if (!resume) {
                    // keep tool_use/tool_result pairing valid for the next turn
                    ObjectNode fix = messages.addObject();
                    fix.put("role", "user");
                    fix.putArray("content").add(truncatedResult(cut, windowFull, tctx));
                    if (persist) sessions.saveMessages(chatId, messages);
                    return new LoopResult(finalText, Outcome.TRUNCATED);
                }
                truncationRetried = complete == 0;
                if (cut != null) toolUses.remove(toolUses.size() - 1);
            } else {
                truncationRetried = false;
                continuations = 0;
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
                            fixed.add(toolResult(use.path("id").asText(), "Not executed: the response ended with "
                                    + stop + ".", true));
                        }
                        if (persist) sessions.saveMessages(chatId, messages);
                    }
                    return new LoopResult(finalText, "refusal".equals(stop) ? Outcome.REFUSED : Outcome.TRUNCATED);
                }
                // end_turn and stop_sequence both simply end the turn
                if (!"tool_use".equals(stop) || toolUses.isEmpty()) {
                    // queued steering is not lost when the answer was final: it becomes the next turn
                    if (injectSteering(inbox, messages, sink, chatId, persist) || injectFollowUp(inbox, messages, sink,
                            chatId, persist)) {
                        continue;
                    }
                    return new LoopResult(finalText, Outcome.COMPLETED);
                }
            }

            String intent = lastUserText(messages);
            scope.intent = intent;
            List<Future<ObjectNode>> futures = new ArrayList<>();
            for (JsonNode use : toolUses) {
                futures.add(toolExecutor.submit(() -> executeTool(use, byName, refused, tctx, mode, sink, cancelled, interactive, intent, label)));
            }
            // cancel interrupts the running tool threads; every tool_use still gets a tool_result below
            Runnable unhook = token.onCancel(() -> futures.forEach(f -> f.cancel(true)));
            ObjectNode resultMsg = messages.addObject();
            resultMsg.put("role", "user");
            ArrayNode results = resultMsg.putArray("content");
            try {
                for (int i = 0; i < futures.size(); i++) {
                    String id = toolUses.get(i).path("id").asText();
                    try {
                        results.add(futures.get(i).get());
                    } catch (java.util.concurrent.CancellationException e) {
                        results.add(toolResult(id, "Cancelled by user", true));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        futures.forEach(f -> f.cancel(true));
                        results.add(toolResult(id, "Cancelled by user", true));
                    } catch (Exception e) {
                        results.add(toolResult(id, "Tool execution failed: " + e.getMessage(), true));
                    }
                }
            } finally {
                unhook.run();
            }
            if (cut != null) results.add(truncatedResult(cut, windowFull, tctx));
            if (persist) sessions.saveMessages(chatId, messages);
            // every tool_use has its tool_result: a safe boundary for the user's steering messages
            injectSteering(inbox, messages, sink, chatId, persist);
        }
        sink.emit("warning", mapper.createObjectNode().put("message", "Stopped after " + turnLimit[0] + " turns."));
        return new LoopResult(finalText, Outcome.MAX_TURNS);
    }

    /**
     * The turn limit was reached: asks the user whether to go on for another {@code maxTurns}. Only an interactive run
     * asks; no answer (timeout, cancel, reject) stops the run as before.
     */
    private boolean continueAfterLimit(int[] turnLimit, int turn, String chatId, boolean interactive, EventSink sink,
                                       BooleanSupplier cancelled, String label) {
        if (!interactive || cancelled.getAsBoolean()) return false;
        String title = (label == null ? "" : "[" + label + "] ") + "Reached " + turn + " turns";
        String preview = "The agent has made " + turn + " model calls without finishing. How many more turns to allow?";
        ApprovalService.ApprovalDecision d = approvals.await(chatId, "continue", title, preview,
                String.valueOf(config.maxTurns()), a -> {
            ObjectNode ev = mapper.valueToTree(a);
            if (label != null) ev.put("agent", label);
            sink.emit("approval_request", ev);
        }, cancelled);
        metrics.approval("continue", d.approved());
        if (!d.approved()) return false;
        turnLimit[0] += turnsAllowed(d.edited());
        return true;
    }

    /** The turns the user typed for the continue question; the default {@code maxTurns} when empty or invalid. */
    private int turnsAllowed(String edited) {
        try {
            int n = Integer.parseInt(edited == null ? "" : edited.strip());
            return n > 0 ? Math.min(n, 10_000) : config.maxTurns();
        } catch (NumberFormatException e) {
            return config.maxTurns();
        }
    }

    /**
     * A max_tokens stop well below the output limit: the server ran out of context window (llama.cpp reports both as
     * "length"). Unknown when the server reports no output tokens.
     */
    private boolean stoppedByWindow(Usage usage) {
        return usage != null && usage.outputTokens() > 0 && usage.outputTokens() < config.maxTokens() * 9L / 10;
    }

    /** The error result of the tool call a max_tokens stop cut off; a cut-off write_file keeps what arrived. */
    private ObjectNode truncatedResult(JsonNode cut, boolean windowFull, ToolContext ctx) {
        String saved = PartialWrite.save(cut, ctx);
        return toolResult(cut.path("id").asText(), "Not executed: your response was cut off while writing this call ("
                + (windowFull ? "the context window is full" : "output limit of " + config.maxTokens() + " tokens")
                + "), so its input is incomplete."
                + (saved != null ? saved : " Retry this one with less output per response: write large files in "
                + "parts, write_file with the first part then write_file append: true for the rest."), true);
    }

    /** Appends every queued steering message as a user message. @return whether any was applied */
    private boolean injectSteering(Inbox inbox, ArrayNode messages, EventSink sink, String chatId, boolean persist) {
        if (inbox == null) return false;
        boolean any = false;
        for (String text = inbox.pollSteering(); text != null; text = inbox.pollSteering()) {
            appendUserText(messages, text);
            sink.emit("steer_applied", mapper.createObjectNode().put("text", text).put("kind", "steer"));
            any = true;
        }
        if (any && persist) sessions.saveMessages(chatId, messages);
        return any;
    }

    /** Appends the next follow-up as a user message. @return whether one was applied */
    private boolean injectFollowUp(Inbox inbox, ArrayNode messages, EventSink sink, String chatId, boolean persist) {
        if (inbox == null) return false;
        String text = inbox.pollFollowUp();
        if (text == null) return false;
        appendUserText(messages, text);
        sink.emit("steer_applied", mapper.createObjectNode().put("text", text).put("kind", "followup"));
        if (persist) sessions.saveMessages(chatId, messages);
        return true;
    }

    private void appendUserText(ArrayNode messages, String text) {
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.putArray("content").addObject().put("type", "text").put("text", text);
    }

    private ObjectNode executeTool(JsonNode use, Map<String, HarnessTool> byName, java.util.Set<String> refused, ToolContext ctx, PermissionMode mode,
                                   EventSink sink, BooleanSupplier cancelled, boolean interactive,
                                   String intent, String label) {
        String id = use.path("id").asText();
        String name = use.path("name").asText();
        JsonNode input = use.path("input");
        ObjectNode call = mapper.createObjectNode();
        call.put("id", id);
        call.put("name", name);
        call.set("input", input);
        sink.emit("tool_call", call);

        ObjectNode result;
        long toolStart = System.nanoTime();
        HarnessTool tool = byName.get(name);
        org.kendar.llm.harness.tools.ToolInputValidator.Outcome check = tool == null ? null : checkInput(tool, input, ctx);
        try {
            if (tool == null) {
                result = refused.contains(name)
                        ? toolResult(id, "Tool unavailable for agent", true)
                        : toolResult(id, "Unknown tool '" + name + "'", true);
            } else if (cancelled.getAsBoolean()) {
                result = toolResult(id, "Cancelled by user", true);
            } else if (!check.valid()) {
                result = toolResult(id, "Invalid arguments for '" + name + "': " + String.join("; ", check.errors())
                        + ". Expected input schema: " + tool.inputSchema(), true);
            } else {
                input = check.input();
                Authz az = authorize(tool, name, input, ctx, mode, sink, cancelled, interactive, intent, label, false);
                input = az.input();
                if (az.denial() != null) {
                    result = toolResult(id, az.denial(), true);
                } else {
                    try {
                        result = runTool(tool, input, ctx.withToolUseId(id), id);
                    } catch (SandboxUnavailableException e) {
                        result = sandboxUnavailable(e, tool, input, ctx, id, sink, cancelled, interactive, label);
                    }
                    result = withHookContext(result, az.pre().addedContext(),
                            hooks.run(HookContext.postTool(ctx.chatId(), name, input, result.path("content").asText(""),
                                    result.path("is_error").asBoolean(false),
                                    (System.nanoTime() - toolStart) / 1_000_000L, ctx.parent())).addedContext());
                }
            }
        } catch (RuntimeException e) {
            // a hook, the permission check or the sandbox fallback failed: the call still gets its result event
            log.warn("Tool call {} ({}) failed outside the tool: {}", id, name, e.toString());
            result = toolResult(id, "Tool execution failed: " + e.getMessage(), true);
        }

        boolean failed = result.path("is_error").asBoolean(false);
        metrics.toolCall(tool == null ? "unknown" : name, failed ? "error" : "ok",
                java.time.Duration.ofNanos(System.nanoTime() - toolStart));
        ObjectNode ev = mapper.createObjectNode();
        ev.put("id", id);
        ev.put("name", name);
        ev.put("isError", result.path("is_error").asBoolean(false));
        String out = result.path("content").asText("");
        int eventMax = config.output().eventMaxChars();
        ev.put("output", out.length() > eventMax ? out.substring(0, eventMax) + "…" : out);
        sink.emit("tool_result", ev);
        return result;
    }

    /** Outcome of {@link #authorize}: {@code denial} is null when the call may run. */
    private record Authz(String denial, HookResult pre, JsonNode input) {
    }

    /**
     * Hooks, permission decision and approval of one call, shared by the model loop and the script scope.
     *
     * @param free built-in file tools called from a script: no gate, no prompt, no taint gating; deny rules, hook
     *             denials and plan confinement still apply
     */
    private Authz authorize(HarnessTool tool, String name, JsonNode input, ToolContext ctx, PermissionMode mode,
                            EventSink sink, BooleanSupplier cancelled, boolean interactive, String intent,
                            String label, boolean free) {
        HookResult pre = hooks.run(HookContext.preTool(ctx.chatId(), name, input, ctx.parent()));
        if (pre.modifiedInput() != null) input = pre.modifiedInput();
        // dispatch check: a call must be inside the user's tools, even if it names a hidden one; also a call made by a
        // script (run_js does not widen what the user may call)
        org.kendar.llm.harness.auth.UserPolicy policy = identity.policyOf(ctx.chatId());
        if (!policy.allows(tool)) {
            return new Authz("Not available to this user: " + name, pre, input);
        }
        if (!sessions.chatAllowsTool(ctx.chatId(), name)) {
            return new Authz("Tool disabled for this chat: " + name, pre, input);
        }
        PermissionGate userGate = policy.approval() == null ? gate : new PermissionGate(policy.approval(), gate.rules());
        Decision decision = free ? Decision.ALLOW : userGate.check(mode, tool);
        if (!free && decision == Decision.ALLOW && mode == PermissionMode.AUTO && classifier != null
                && tool.kind() != ToolKind.READ_ONLY && !pre.denied()) {
            // auto mode: the fast model may wave the call through; anything else goes to the user
            ToolClassifier.Verdict v = classifier.classify(name, input, ctx.workDir(), intent);
            if (v != ToolClassifier.Verdict.SAFE) decision = Decision.ASK;
            sink.emit("classifier", mapper.createObjectNode().put("tool", name).put("verdict", v.name()));
        }
        // settings rules: deny wins in every mode and over any grant; ask forces the question; allow skips it
        PermissionRules.Match rule = gate.rules().match(name, input, ctx.workDir());
        if (!free && rule != null && rule.kind() == PermissionRules.Kind.ASK && decision == Decision.ALLOW) {
            decision = Decision.ASK;
        }
        // a grant only replaces the question; deny rules, hooks and plan confinement stay as they are
        if (decision == Decision.ASK && (approvals.granted(ctx.chatId(), name)
                || approvals.commandGranted(ctx.chatId(), name, commandOf(name, input))
                || approvals.dirGranted(ctx.chatId(), writeDirOf(tool, input, ctx)))) decision = Decision.ALLOW;
        if (rule != null && rule.kind() == PermissionRules.Kind.ALLOW && decision == Decision.ASK
                && mode != PermissionMode.AUTO) {
            decision = Decision.ALLOW;
        }
        // outside content is in the context: side effects need an explicit yes, even over grants and allow rules
        if (!free && decision == Decision.ALLOW && tool.kind() != ToolKind.READ_ONLY && isTainted(ctx.chatId())
                && (rule == null || rule.kind() != PermissionRules.Kind.DENY)) {
            decision = Decision.ASK;
            sink.emit("warning", mapper.createObjectNode().put("message", "Confirming '" + name
                    + "': the conversation holds untrusted tool output that could contain injected instructions"));
        }
        if (decision == Decision.ASK && !interactive) decision = Decision.DENY;
        if (pre.denied()) return new Authz("Denied by a hook: " + pre.denyReason(), pre, input);
        if (rule != null && rule.kind() == PermissionRules.Kind.DENY) {
            return new Authz("Denied by permission rule: " + rule.rule(), pre, input);
        }
        if (decision == Decision.DENY) return new Authz("Denied by permission policy (mode " + mode + ")", pre, input);
        if (decision == Decision.ASK) {
            if (!approve(tool, input, ctx, sink, cancelled, label)) {
                return new Authz("The user rejected this tool call", pre, input);
            }
            approvals.grantCommand(ctx.chatId(), name, commandOf(name, input));
        }
        return new Authz(null, pre, input);
    }

    /** Validates (and in lenient mode repairs) the arguments; a failing validator never blocks the call. */
    private org.kendar.llm.harness.tools.ToolInputValidator.Outcome checkInput(HarnessTool tool, JsonNode input,
                                                                              ToolContext ctx) {
        try {
            var o = inputValidator.validate(tool, input, validationMode(ctx.chatId()));
            return o.input() == null ? new org.kendar.llm.harness.tools.ToolInputValidator.Outcome(input, o.errors()) : o;
        } catch (RuntimeException e) {
            return org.kendar.llm.harness.tools.ToolInputValidator.Outcome.ok(input);
        }
    }

    /** The shell command line of a bash call, else null: only commands are granted verbatim. */
    /** The work-dir-relative directory a file-writing call targets, or null when none can be offered or granted. */
    private static String writeDirOf(HarnessTool tool, JsonNode input, ToolContext ctx) {
        if (tool.kind() != ToolKind.FILE_EDIT || ctx.readOnlyWork() || ctx.files() != null || input == null) return null;
        return ApprovalService.writeDirOf(ctx.workDir(), input.path("path").asText(null));
    }

    private static String commandOf(String toolName, JsonNode input) {
        return "bash".equals(toolName) && input != null && input.hasNonNull("command")
                ? input.get("command").asText() : null;
    }

    private boolean approve(HarnessTool tool, JsonNode input, ToolContext ctx, EventSink sink,
                            BooleanSupplier cancelled, String label) {
        String preview;
        try {
            preview = tool.preview(ctx, input);
        } catch (Exception e) {
            preview = input.toString();
        }
        String title = label == null ? tool.name() : "[" + label + "] " + tool.name();
        if (ctx.parent() != null) title += " (from " + ctx.parent().replaceFirst(":.*", "") + ")";
        String writeDir = writeDirOf(tool, input, ctx);
        ApprovalService.ApprovalDecision d = approvals.await(ctx.chatId(), "tool", title, preview, null, a -> {
            ObjectNode ev = mapper.valueToTree(a);
            if (label != null) ev.put("agent", label);
            if (writeDir != null) ev.put("writeDir", writeDir);
            sink.emit("approval_request", ev);
        }, cancelled, () -> !isTainted(ctx.chatId()) && (approvals.granted(ctx.chatId(), tool.name())
                || approvals.dirGranted(ctx.chatId(), writeDir)));
        boolean ok = d.approved();
        if (ok && d.rememberDir()) {
            // A typed path (carried in "edited") replaces the offered directory; an invalid one (outside the work
            // dir, absolute) rejects the call: the user approved a grant that cannot be given
            boolean typed = d.edited() != null && !d.edited().isBlank();
            String dir = typed ? ApprovalService.grantDirOf(ctx.workDir(), d.edited()) : writeDir;
            if (typed && dir == null) {
                sink.emit("warning", mapper.createObjectNode().put("message",
                        "Invalid folder '" + d.edited() + "': the call was rejected"));
                ok = false;
            } else {
                approvals.grantDir(ctx.chatId(), dir);
            }
        }
        if (ok && d.remember()) approvals.grant(ctx.chatId(), tool.name());
        metrics.approval("tool", ok);
        return ok;
    }

    /** Appends the context a hook asked to add to the content of a tool result. */
    private ObjectNode withHookContext(ObjectNode result, String... added) {
        StringBuilder content = new StringBuilder(result.path("content").asText(""));
        for (String a : added) if (a != null) content.append("\n\n").append(a);
        result.put("content", content.toString());
        return result;
    }

    /**
     * The container sandbox cannot be used: ask the user whether to continue without isolation, instead of failing.
     * Once agreed, the chat stays on the host sandbox and the tool is retried with host paths.
     */
    private ObjectNode sandboxUnavailable(SandboxUnavailableException e, HarnessTool tool, JsonNode input,
                                          ToolContext ctx, String id, EventSink sink, BooleanSupplier cancelled,
                                          boolean interactive, String label) {
        String chatId = ctx.chatId();
        if (!sandbox.localFallbackActive(chatId)) {
            if (!interactive || !config.sandbox().localDirSandboxAllowed()) {
                return toolResult(id, "Error: " + e.getMessage(), true);
            }
            String preview = e.getMessage() + "\n\nContinue WITHOUT isolation? Commands would run as your user "
                    + "directly on this machine, in " + sandbox.location() + ".";
            ApprovalService.ApprovalDecision d = approvals.await(chatId, "sandbox",
                    "Docker is unavailable: run without isolation?", preview, null, a -> {
                        ObjectNode ev = mapper.valueToTree(a);
                        if (label != null) ev.put("agent", label);
                        sink.emit("approval_request", ev);
                    }, cancelled);
            metrics.approval("sandbox", d.approved());
            if (!d.approved() || !sandbox.allowLocalFallback(chatId)) {
                return toolResult(id, "Error: " + e.getMessage() + " (the user declined to run without isolation)", true);
            }
        }
        String scratchId = ctx.scratchDir() == null || ctx.scratchDir().getFileName() == null ? "main"
                : ctx.scratchDir().getFileName().toString();
        ToolContext local = new ToolContext(chatId, sandbox.workDir(chatId), sandbox.scratchDir(chatId, scratchId),
                ctx.readOnlyWork(), id, ctx.mode(), ctx.run(), sandbox.files(chatId, scratchId), ctx.parent(),
                ctx.scope()).withHosts(ctx.hosts());
        ObjectNode r = runTool(tool, input, local, id);
        r.put("content", "[running without isolation on the host: " + sandbox.workDir(chatId) + "]\n"
                + r.path("content").asText(""));
        return r;
    }

    private ObjectNode runTool(HarnessTool tool, JsonNode input, ToolContext ctx, String id) {
        return runTool(tool, input, ctx, id, false);
    }

    /** @param raw the caller wants the full output: no truncation, no sanitizing (taint is still decided) */
    private ObjectNode runTool(HarnessTool tool, JsonNode input, ToolContext ctx, String id, boolean raw) {
        try {
            String out = tool.execute(ctx, input);
            if (out == null) out = "";
            if (raw) {
                sanitizeOutput(tool, ctx, out); // taint decision only
                return toolResult(id, out, false);
            }
            int max = config.output().toolMaxChars();
            if (out.length() > max) {
                out = tool.truncation() == org.kendar.llm.harness.tools.TruncationMode.TAIL
                        ? "…[truncated " + (out.length() - max) + " chars]\n" + out.substring(out.length() - max)
                        : out.substring(0, max) + "\n…[truncated " + (out.length() - max) + " chars]";
            }
            return toolResult(id, sanitizeOutput(tool, ctx, out), false);
        } catch (SandboxUnavailableException e) {
            throw e;
        } catch (Exception e) {
            String m = e instanceof java.io.IOException ? e.getClass().getSimpleName() + ": " + e.getMessage() : e.getMessage();
            return toolResult(id, "Error: " + m, true);
        }
    }

    /** Tool groups whose output is produced by the harness or the user's own skills, not by outside content. */
    private static final java.util.Set<String> INTERNAL_GROUPS = java.util.Set.of("Agents", "Skills", "Memory");
    private static final java.util.Set<String> LOCAL_GROUPS = java.util.Set.of("Files", "Shell", "Js");

    /** Cleans and wraps the output of a tool; marks the chat tainted when it may carry outside instructions. */
    private String sanitizeOutput(HarnessTool tool, ToolContext ctx, String out) {
        if (sanitizer.mode() == ToolOutputSanitizer.Mode.OFF || INTERNAL_GROUPS.contains(tool.group())) return out;
        ToolOutputSanitizer.Trust trust = LOCAL_GROUPS.contains(tool.group())
                ? ToolOutputSanitizer.Trust.LOW : ToolOutputSanitizer.Trust.UNTRUSTED;
        ToolOutputSanitizer.Result r = sanitizer.sanitize(tool.name(), trust, out);
        if (r.tainted() && config.output().taintGating()) taint(ctx.chatId(), ctx.toolUseId());
        if (r.flagged()) {
            log.warn("Tool '{}' output flagged as possible prompt injection: {}", tool.name(), r.findings());
        }
        return r.text();
    }

    private ObjectNode toolResult(String id, String content, boolean isError) {
        ObjectNode r = mapper.createObjectNode();
        r.put("type", "tool_result");
        r.put("tool_use_id", id);
        r.put("content", content.isEmpty() ? "(no output)" : content);
        if (isError) r.put("is_error", true);
        return r;
    }
}

package org.kendar.llm.harness;

import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.Hooks;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.kendar.llm.harness.agent.ActiveSubAgent;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.agent.AgentService;
import org.kendar.llm.harness.agent.SubAgentRunner;
import org.kendar.llm.harness.commands.SlashCommands;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.integration.ExternalSources;
import org.kendar.llm.harness.llm.LimitedModelClient;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelSwitch;
import org.kendar.llm.harness.llm.ProviderCatalog;
import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.PermissionRules;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.tools.Checkpoints;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.StorageQuota;
import org.kendar.llm.harness.tools.ValidationMode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Facade of the agent harness: owns one run per chat, persists chats through a
 * {@link org.kendar.llm.harness.session.ChatSessionStore}, and runs a ReAct loop over the standard tools plus the
 * tools injected by the consumer. Built by {@link AgentHarnessBuilder}.
 */
public final class AgentHarness {

    private final AgentService service;
    private final ChatSessionService sessions;
    private final HarnessToolRegistry registry;
    private final ExternalSources sources;
    private final SkillCatalog skills;
    private final SandboxExecutor sandbox;
    private final PermissionRules permissionRules;
    private final ContentWarnings contentWarnings;
    private final SubAgentRunLog subAgentRuns;
    private final ContextStrategy context;
    private final HarnessConfig config;
    private final HarnessPricing pricing;
    private final ProviderCatalog providers;
    private final ModelSwitch modelSwitch;
    private final int maxParallel;
    private final Checkpoints checkpoints;
    private final StorageQuota quota;
    private final ServiceRegistry services;
    private final HarnessAuth auth;
    private final Hooks hooks;
    private final SlashCommands commands = new SlashCommands(this);

    AgentHarness(HarnessParts parts) {
        this.service = parts.service();
        this.sessions = parts.sessions();
        this.registry = parts.registry();
        this.sources = parts.sources();
        this.skills = parts.skills();
        this.sandbox = parts.sandbox();
        this.permissionRules = parts.permissionRules();
        this.contentWarnings = parts.contentWarnings();
        this.subAgentRuns = parts.subAgentRuns();
        this.context = parts.context();
        this.config = parts.config();
        this.pricing = parts.pricing();
        this.providers = parts.providers();
        this.modelSwitch = parts.modelSwitch();
        this.maxParallel = parts.maxParallel();
        this.checkpoints = parts.checkpoints();
        this.quota = parts.quota();
        this.services = parts.services();
        this.auth = parts.auth();
        this.hooks = parts.hooks();
        service.onTurnStart(this::beginTurn);
        // a login started from the web ended: tell the chat it was started from
        commands.onLoginFinished((chatId, msg) -> service.notify(chatId, "login",
                JsonNodeFactory.instance.objectNode().put("message", msg)
                        .put("ok", msg != null && msg.startsWith("Logged in"))));
    }

    /** Starts a user turn asynchronously. @throws AgentService.RunAlreadyActiveException if the chat is busy */
    public void start(String chatId, String text) {
        // the checkpoint of the turn is opened by the run itself (beginTurn), once the run was accepted
        service.start(chatId, text);
    }

    /** Opens the checkpoint of a new user turn: the first message of a run and every queued, steered or follow-up one. */
    private void beginTurn(String chatId) {
        var files = sandbox.files(chatId, null); // non-null: the files are in a container
        if (files == null) checkpoints.beginTurn(chatId, sandbox.workDir(chatId));
        else checkpoints.beginTurn(chatId, files);
    }

    /**
     * Restores the files {@code write_file} and {@code edit_file} changed in the last {@code turns} turns. Files
     * changed by shell commands are not restored. The conversation is told: the turns that made the changes stay in
     * it, and without the note the model takes the restored content for a failed write and writes it again.
     *
     * @throws AgentService.RunAlreadyActiveException if a run is in progress
     */
    public Checkpoints.UndoResult undo(String chatId, int turns) {
        if (isRunning(chatId)) throw new AgentService.RunAlreadyActiveException(chatId);
        var files = sandbox.files(chatId, null);
        Checkpoints.UndoResult result;
        try {
            result = files != null ? checkpoints.undo(chatId, files, turns)
                    : checkpoints.undo(chatId, sandbox.workDir(chatId), turns, config.externalLinks());
        } finally {
            quota.forget(chatId); // restored files change the storage size: measure it again
        }
        if (result.turns() > 0) UndoNote.append(sessions, chatId, result);
        return result;
    }

    /**
     * Stops the MCP clients and releases the sandbox (removes the web containers); a kept CLI container stays. Safe
     * to call twice.
     */
    public void close() {
        try {
            sources.close();
        } finally {
            sandbox.close();
        }
    }

    /**
     * Queues a steering message for the active run, applied after the current round of tool calls; starts a run when
     * the chat is idle.
     */
    public void steer(String chatId, String text) {
        service.steer(chatId, text);
    }

    /** Queues a message applied only when the agent would otherwise finish; starts a run when the chat is idle. */
    public void followUp(String chatId, String text) {
        service.followUp(chatId, text);
    }

    /** @param kill stop a running script at once instead of letting it clean up first */
    public void cancel(String chatId, boolean kill) {
        service.cancel(chatId, kill);
    }

    public boolean isRunning(String chatId) {
        return service.isRunning(chatId);
    }

    /** Sub-agents running now for the chat, oldest first. */
    public List<ActiveSubAgent> activeSubAgents(String chatId) {
        return service.activeSubAgents(chatId);
    }

    /**
     * Deletes a chat with everything kept for it: log, sub-agent runs, and (through the delete listener registered by
     * the builder) context-strategy state, grants, taint and storage accounting.
     */
    public void deleteChat(String chatId) {
        service.cancel(chatId, false);
        subAgentRuns.deleteChat(chatId);
        sessions.delete(chatId);
    }

    /** Chats not updated for {@code days} days, oldest first, never {@code keepChatId}. */
    public List<ChatSessionData> staleChats(int days, String keepChatId) {
        Instant limit = Instant.now().minus(Duration.ofDays(days));
        List<ChatSessionData> out = new ArrayList<>();
        for (ChatSessionData s : sessions.list()) {
            if (!s.chatId().equals(keepChatId) && s.updatedAt().isBefore(limit)) out.add(s);
        }
        Collections.reverse(out); // list() is newest first
        return out;
    }

    /**
     * Deletes the oldest chats beyond {@code limit}, never {@code keepChatId} (which counts toward the limit).
     *
     * @return the number of deleted chats
     */
    public int enforceChatRetention(Integer limit, String keepChatId) {
        if (limit == null || limit < 1) return 0;
        List<ChatSessionData> all = sessions.list(); // newest first
        int deleted = 0;
        for (int i = all.size() - 1; i >= 0 && all.size() - deleted > limit; i--) {
            if (all.get(i).chatId().equals(keepChatId)) continue;
            deleteChat(all.get(i).chatId());
            deleted++;
        }
        return deleted;
    }

    public long lastSeq(String chatId) {
        return service.lastSeq(chatId);
    }

    /** Replays buffered events after {@code afterSeq}, then delivers live ones. @return a handle that unsubscribes */
    public Runnable subscribe(String chatId, long afterSeq, Consumer<AgentEvent> listener) {
        return service.subscribe(chatId, afterSeq, listener);
    }

    public List<ApprovalService.PendingApproval> pendingApprovals(String chatId) {
        return service.pendingApprovals(chatId);
    }

    /**
     * @param remember    allow the tool for the rest of the chat, sub-agents included
     * @param rememberDir allow file writes in the call's directory and below, for this chat
     */
    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited,
                                   boolean remember, boolean rememberDir) {
        return service.resolveApproval(chatId, approvalId, approved, edited, remember, rememberDir);
    }

    /** Manual compaction. @throws AgentService.RunAlreadyActiveException if a run is in progress */
    public CompactResult compact(String chatId, String focus) {
        return service.compact(chatId, focus);
    }

    /** How the arguments of tool calls are checked in this chat: the {@code /validation} choice, else the config. */
    public ValidationMode toolValidation(String chatId) {
        return service.validationMode(chatId);
    }

    public void setToolValidation(String chatId, ValidationMode mode) {
        service.validationMode(chatId, mode);
    }

    /** Estimated size of the conversation of a chat, in tokens. */
    public long contextTokens(String chatId) {
        ChatSessionData s = sessions.get(chatId);
        return context.contextTokens(chatId, s.agentId(), sessions.messages(s));
    }

    /** The model providers: configured, built-in and the session entry. */
    public ProviderCatalog providers() {
        return providers;
    }

    /** Name of the provider the main model currently comes from, or null when it was injected directly. */
    public String currentProvider() {
        return modelSwitch.name();
    }

    /** Result of {@link #switchModel}. */
    public enum SwitchStatus { SWITCHED, NEEDS_COMPACTION, REFUSED }

    public record SwitchResult(SwitchStatus status, String message) {
    }

    /**
     * Swaps the main model for new runs. When the conversation does not fit the new model context window the
     * result is {@code NEEDS_COMPACTION} (the caller asks the user), or {@code REFUSED} when compaction is not
     * available; call again with {@code compactFirst} after the user agreed.
     *
     * @throws AgentService.RunAlreadyActiveException if a run is in progress in the chat
     */
    public SwitchResult switchModel(String chatId, String providerName, boolean compactFirst) {
        if (isRunning(chatId)) throw new AgentService.RunAlreadyActiveException(chatId);
        ProviderSettings p = providers.find(providerName);
        if (p == null) return new SwitchResult(SwitchStatus.REFUSED, "Unknown provider " + providerName);
        Integer window = p.contextTokens();
        if (window != null && contextTokens(chatId) > window * 9L / 10) {
            boolean canCompact = context.kind() != ContextStrategyKind.MNEMONIC;
            if (!canCompact) {
                return new SwitchResult(SwitchStatus.REFUSED, "The conversation (~" + contextTokens(chatId)
                        + " tokens) does not fit " + providerName + " (" + window
                        + " tokens) and this context strategy cannot compact; model unchanged");
            }
            if (!compactFirst) {
                return new SwitchResult(SwitchStatus.NEEDS_COMPACTION, "The conversation (~" + contextTokens(chatId)
                        + " tokens) does not fit " + providerName + " (" + window + " tokens). Compact first?");
            }
            compact(chatId, "");
            if (contextTokens(chatId) > window * 9L / 10) {
                return new SwitchResult(SwitchStatus.REFUSED, "Still ~" + contextTokens(chatId)
                        + " tokens after compacting, more than " + providerName + " holds; model unchanged");
            }
        }
        ModelClient next = providers.client(p);
        if (maxParallel > 0) next = new LimitedModelClient(next, maxParallel);
        modelSwitch.switchTo(next, providerName, window);
        return new SwitchResult(SwitchStatus.SWITCHED, "Model: " + providerName + " (" + p.toLlmSettings().model() + ")");
    }

    /** The context strategy in use (compaction, mnemonic or a custom one). */
    public ContextStrategy contextStrategy() {
        return context;
    }

    /** Size above which the context gets compacted. */
    public long contextBudget() {
        return config.contextBudgetTokens();
    }

    /** Estimated cost in USD of the tokens used so far by a chat (0 when no prices are configured). */
    public double cost(ChatSessionData s) {
        return pricing.estimate(s.inputTokens(), s.outputTokens(), s.cacheReadTokens(), s.cacheCreationTokens());
    }

    /**
     * Runs the hooks of an event the host raises outside a chat ({@link org.kendar.llm.harness.hooks.HookEvent#ROLE_CHANGED}):
     * observation only, a failing hook is logged and ignored.
     */
    public void runHooks(HookContext ctx) {
        hooks.run(ctx);
    }

    public ChatSessionService sessions() {
        return sessions;
    }

    /** Logins, OAuth sources and the token store. */
    public HarnessAuth auth() {
        return auth;
    }

    /** Services published by plugins and the host (see {@code PluginRegistrar.services()}). */
    public ServiceRegistry services() {
        return services;
    }

    /** Slash commands shared by the command line and the web front end. */
    public SlashCommands commands() {
        return commands;
    }

    public HarnessToolRegistry tools() {
        return registry;
    }

    /** The MCP servers and REST APIs: enable, disable and check them at runtime. */
    public ExternalSources sources() {
        return sources;
    }

    /** The skills: enable or disable them at runtime. */
    public SkillCatalog skills() {
        return skills;
    }

    /**
     * The files loaded at startup (settings, skills, instruction files, agent prompts, system prompt additions) in
     * which suspicious content was found; they were loaded unchanged. For the front ends to show to the user, never
     * to store with a chat. Files loaded during a chat are told by a {@code content_warning} event instead.
     */
    public List<ContentWarnings.Warning> contentWarnings() {
        return contentWarnings.all();
    }

    /** The active settings permission rules (read-only). */
    public PermissionRules permissionRules() {
        return permissionRules;
    }

    public SandboxExecutor sandbox() {
        return sandbox;
    }

    /** Working directory of a chat (root of the file tools). */
    public Path workDir(String chatId) {
        return sandbox.workDir(chatId);
    }

    /** Scratch directory of the main agent of a chat. */
    public Path scratchDir(String chatId) {
        return sandbox.scratchDir(chatId, SubAgentRunner.scratchId(sessions.get(chatId).agentId()));
    }

    public SubAgentRunLog subAgentRuns() {
        return subAgentRuns;
    }
}

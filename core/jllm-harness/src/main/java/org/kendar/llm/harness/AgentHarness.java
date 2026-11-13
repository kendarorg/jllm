package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.agent.*;
import org.kendar.llm.harness.commands.CustomCommand;
import org.kendar.llm.harness.commands.SlashCommands;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.context.ContextStrategyFactory;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.compaction.CompactionStrategy;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.context.mnemonic.InMemoryMnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStrategy;
import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.instructions.InstructionLoader;
import org.kendar.llm.harness.integration.ExternalSources;
import org.kendar.llm.harness.llm.LimitedModelClient;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelClients;
import org.kendar.llm.harness.permission.*;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginLoader;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.kendar.llm.harness.sandbox.LocalDirSandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.*;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.skills.SkillTool;
import org.kendar.llm.harness.tools.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Facade of the agent harness: owns one run per chat, persists chats through a {@link ChatSessionStore}, and runs a
 * ReAct loop over the standard tools plus the tools injected by the consumer.
 */
public final class AgentHarness {

    private static final Logger LOG = LoggerFactory.getLogger(AgentHarness.class);

    private final AgentService service;
    private final ChatSessionService sessions;
    private final HarnessToolRegistry registry;
    private final ExternalSources sources;
    private final SkillCatalog skills;
    private final SandboxExecutor sandbox;
    private PermissionRules permissionRules = PermissionRules.none();
    private final SubAgentRunLog subAgentRuns;
    private final ContextStrategy context;
    private final HarnessConfig config;
    private final HarnessPricing pricing;
    private org.kendar.llm.harness.llm.ProviderCatalog providers;
    private org.kendar.llm.harness.llm.ModelSwitch modelSwitch;
    private int maxParallel;
    private org.kendar.llm.harness.tools.Checkpoints checkpoints;
    private org.kendar.llm.harness.tools.StorageQuota quota;
    private final SlashCommands commands = new SlashCommands(this);

    private AgentHarness(AgentService service, ChatSessionService sessions, HarnessToolRegistry registry,
                         ExternalSources sources, SkillCatalog skills, SandboxExecutor sandbox, SubAgentRunLog subAgentRuns, ContextStrategy context,
                         HarnessConfig config, HarnessPricing pricing) {
        this.context = context;
        this.config = config;
        this.pricing = pricing;
        this.service = service;
        this.sessions = sessions;
        this.registry = registry;
        this.sources = sources;
        this.skills = skills;
        this.sandbox = sandbox;
        this.subAgentRuns = subAgentRuns;
    }

    public static Builder builder() {
        return new Builder();
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
     * changed by shell commands are not restored.
     *
     * @throws AgentService.RunAlreadyActiveException if a run is in progress
     */
    public org.kendar.llm.harness.tools.Checkpoints.UndoResult undo(String chatId, int turns) {
        if (isRunning(chatId)) throw new AgentService.RunAlreadyActiveException(chatId);
        var files = sandbox.files(chatId, null);
        try {
            if (files != null) return checkpoints.undo(chatId, files, turns);
            return checkpoints.undo(chatId, sandbox.workDir(chatId), turns);
        } finally {
            if (quota != null) quota.forget(chatId); // restored files change the storage size: measure it again
        }
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

    public void cancel(String chatId) {
        service.cancel(chatId);
    }

    /** @param kill stop a running script at once instead of letting it clean up first */
    public void cancel(String chatId, boolean kill) {
        service.cancel(chatId, kill);
    }

    public boolean isRunning(String chatId) {
        return service.isRunning(chatId);
    }

    /** Sub-agents running now for the chat, oldest first. */
    public java.util.List<org.kendar.llm.harness.agent.AgentLoop.ActiveSubAgent> activeSubAgents(String chatId) {
        return service.activeSubAgents(chatId);
    }

    /**
     * Deletes a chat with everything kept for it: log, sub-agent runs, and (through the delete listener registered by
     * the builder) context-strategy state, grants, taint and storage accounting.
     */
    public void deleteChat(String chatId) {
        service.cancel(chatId);
        subAgentRuns.deleteChat(chatId);
        sessions.delete(chatId);
    }

    /** Chats not updated for {@code days} days, oldest first, never {@code keepChatId}. */
    public List<ChatSessionData> staleChats(int days, String keepChatId) {
        java.time.Instant limit = java.time.Instant.now().minus(java.time.Duration.ofDays(days));
        List<ChatSessionData> out = new ArrayList<>();
        for (ChatSessionData s : sessions.list()) {
            if (!s.chatId().equals(keepChatId) && s.updatedAt().isBefore(limit)) out.add(s);
        }
        java.util.Collections.reverse(out); // list() is newest first
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

    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited) {
        return service.resolveApproval(chatId, approvalId, approved, edited);
    }

    /** @param remember allow the tool for the rest of the chat, sub-agents included */
    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited,
                                   boolean remember) {
        return service.resolveApproval(chatId, approvalId, approved, edited, remember);
    }

    /** @param rememberDir allow file writes in the call's directory and below, for this chat */
    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited,
                                   boolean remember, boolean rememberDir) {
        return service.resolveApproval(chatId, approvalId, approved, edited, remember, rememberDir);
    }

    /** Manual compaction. @throws AgentService.RunAlreadyActiveException if a run is in progress */
    public CompactResult compact(String chatId, String focus) {
        return service.compact(chatId, focus);
    }

    /** How the arguments of tool calls are checked in this chat: the {@code /validation} choice, else the config. */
    public org.kendar.llm.harness.tools.ValidationMode toolValidation(String chatId) {
        return service.validationMode(chatId);
    }

    public void setToolValidation(String chatId, org.kendar.llm.harness.tools.ValidationMode mode) {
        service.validationMode(chatId, mode);
    }

    /** Estimated size of the conversation of a chat, in tokens. */
    public long contextTokens(String chatId) {
        ChatSessionData s = sessions.get(chatId);
        return context.contextTokens(chatId, s.agentId(), sessions.messages(s));
    }

    /** The model providers: configured, built-in and the session entry. */
    public org.kendar.llm.harness.llm.ProviderCatalog providers() {
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
        org.kendar.llm.harness.llm.ProviderSettings p = providers.find(providerName);
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

    public ChatSessionService sessions() {
        return sessions;
    }

    /** Slash commands shared by the command line and the web front end. */
    private java.util.Map<String, org.kendar.llm.harness.auth.OAuthLogin> logins = java.util.Map.of();
    /** Shared with {@link ExternalSources}: a login and the MCP clients see the same tokens. */
    private org.kendar.llm.harness.auth.TokenStore tokenStore;
    private org.kendar.llm.harness.auth.OAuthSources oauth;
    private boolean multiUser;

    /** The logins {@code /login} can run: discovered {@link org.kendar.llm.harness.auth.OAuthLogin} services and MCP servers with oauth. */
    public java.util.Map<String, org.kendar.llm.harness.auth.OAuthLogin> logins() {
        return logins;
    }

    public org.kendar.llm.harness.auth.TokenStore tokenStore() {
        return tokenStore;
    }

    /** The REST APIs and MCP servers with OAuth: their logins, and which are per user. */
    public org.kendar.llm.harness.auth.OAuthSources oauth() {
        return oauth;
    }

    /** True when the harness serves several users (a user policy resolver was given). */
    public boolean multiUser() {
        return multiUser;
    }

    /**
     * The store key of a login: the principal for a per-user source, else null (the single user, or the login
     * shared by every user).
     */
    public String loginKey(String principalId, String name) {
        return principalId != null && oauth.perUser(name) ? principalId : null;
    }

    /** Stores a login's token and drops what was cached for it. */
    public void saveLogin(String principalId, String name, org.kendar.llm.harness.auth.OAuthToken token) {
        String key = loginKey(principalId, name);
        tokenStore.put(key, name, token);
        oauth.resolver().forget(key, name);
    }

    /**
     * Removes a login: the token, what is cached for it, and the caller's MCP session on that server.
     *
     * @return false when there was no stored login
     */
    public boolean logout(String principalId, String name) {
        String key = loginKey(principalId, name);
        boolean had = tokenStore.remove(key, name);
        oauth.resolver().forget(key, name);
        if (name.startsWith("mcp:") && key != null) sources.closeSession(name.substring(4), key);
        return had;
    }

    /** Tells a chat that a login it started ended: event {@code login {source, ok, message}}. */
    public void loginFinished(String chatId, String source, boolean ok, String message) {
        if (chatId == null || service == null) return;
        try {
            service.notify(chatId, "login", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("source", source).put("ok", ok)
                    .put("message", message));
        } catch (RuntimeException e) {
            // the chat is gone: nothing to tell
        }
    }

    /** Removes every token of a deleted user and closes their MCP sessions. */
    public void forgetUser(String principalId) {
        if (principalId == null) return;
        tokenStore.removeAll(principalId);
        oauth.resolver().forgetAll(principalId);
        sources.closeSessions(principalId);
    }

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

    /** The active settings permission rules (read-only). */
    public PermissionRules permissionRules() {
        return permissionRules;
    }

    public SandboxExecutor sandbox() {
        return sandbox;
    }

    /** Working directory of a chat (root of the file tools). */
    public java.nio.file.Path workDir(String chatId) {
        return sandbox.workDir(chatId);
    }

    /** Scratch directory of the main agent of a chat. */
    public java.nio.file.Path scratchDir(String chatId) {
        return sandbox.scratchDir(chatId, org.kendar.llm.harness.agent.AgentLoop.scratchId(sessions().get(chatId).agentId()));
    }

    public SubAgentRunLog subAgentRuns() {
        return subAgentRuns;
    }

    public static final class Builder {
        private ModelClient modelClient;
        private final List<HarnessTool> tools = new ArrayList<>();
        private boolean standardTools = true;
        private final java.util.Set<String> removedTools = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> replacedTools = new java.util.LinkedHashSet<>();
        private final List<java.util.function.Consumer<java.util.Collection<String>>> nameChecks = new ArrayList<>();
        private ModelClient fastClient;
        private ChatSessionStore sessionStore = new InMemoryChatSessionStore();
        private SubAgentRunLog subAgentRunLog = new InMemorySubAgentRunLog();
        private ToolApprovalPolicy approvalPolicy = ToolApprovalPolicy.byKind();
        private org.kendar.llm.harness.auth.CredentialResolver credentials;
        private org.kendar.llm.harness.auth.TokenStore tokenStore;
        private Path tokenStoreDir;
        /** The OAuth sources of the harness being built. */
        private org.kendar.llm.harness.auth.OAuthSources builtOAuth;
        private org.kendar.llm.harness.auth.UserPolicyResolver userPolicies;
        private java.util.function.Function<String, org.kendar.llm.harness.auth.HarnessPrincipal> principalDirectory;
        private SandboxExecutor sandbox;
        private HarnessMetrics metrics = HarnessMetrics.noop();
        private HarnessConfig config = HarnessConfig.defaults();
        private HarnessPricing pricing = new HarnessPricing(0, 0, 0, 0);
        private String systemPrompt = "";
        private String systemPromptReplace;
        private boolean smallModel;
        private ObjectMapper mapper = new ObjectMapper();
        private HarnessSettings settings = HarnessSettings.empty();
        private final Hooks hooks = new Hooks();
        private final List<CommandSpec> commands = new ArrayList<>();
        private ContextStrategyFactory contextStrategy;
        private MnemonicStore mnemonicStore = new InMemoryMnemonicStore();
        private org.kendar.llm.harness.context.scope.ScopeStrategy.Options scopeOptions =
                org.kendar.llm.harness.context.scope.ScopeStrategy.Options.defaults();
        private org.kendar.llm.harness.state.StateStore stateStore = new org.kendar.llm.harness.state.InMemoryStateStore();
        private String activeProvider;
        private final List<org.kendar.llm.harness.tools.ToolInputValidator> inputValidators = new ArrayList<>();
        private boolean defaultInputValidator = true;
        private org.kendar.llm.harness.llm.ProviderSettings sessionProvider;
        private final org.kendar.llm.harness.tools.DocumentReaders documentReaders =
                new org.kendar.llm.harness.tools.DocumentReaders();
        private final List<HarnessPlugin> explicitPlugins = new ArrayList<>();
        private boolean discover;
        private Path pluginDir;
        private boolean pluginDirExplicit;
        private PluginLoader.Result pluginSummary;

        private record CommandSpec(String name, String description, CustomCommand command) {
        }

        private Builder() {
        }

        /** Small model for the auto-mode classifier, summaries and tool-group selection; default: settings' fastModel. */
        public Builder fastModelClient(ModelClient client) {
            this.fastClient = client;
            return this;
        }

        /** Names the provider the injected {@link #modelClient} comes from, for {@code /model}. */
        public Builder activeProvider(String name) {
            this.activeProvider = name;
            return this;
        }

        /** A provider that exists for this session only (the {@code custom} entry of {@code /model}). */
        public Builder sessionProvider(org.kendar.llm.harness.llm.ProviderSettings provider) {
            this.sessionProvider = provider;
            return this;
        }

        public Builder modelClient(ModelClient client) {
            this.modelClient = client;
            return this;
        }

        /** Tools added to the standard ones. May be called more than once. */
        public Builder tools(List<? extends HarnessTool> tools) {
            this.tools.addAll(tools);
            return this;
        }

        /**
         * Replaces the default (JSON Schema) check of tool-call arguments, and any validator added before.
         * It decides what each {@link org.kendar.llm.harness.tools.ValidationMode} means for it.
         */
        public Builder toolInputValidator(org.kendar.llm.harness.tools.ToolInputValidator validator) {
            this.inputValidators.clear();
            this.inputValidators.add(java.util.Objects.requireNonNull(validator));
            this.defaultInputValidator = false;
            return this;
        }

        /** Adds a check that runs after the default one and sees the input as repaired by it. */
        public Builder addToolInputValidator(org.kendar.llm.harness.tools.ToolInputValidator validator) {
            this.inputValidators.add(java.util.Objects.requireNonNull(validator));
            return this;
        }

        /** Runs {@code hook} on every {@code event}, after the hooks added before it. */
        public Builder hook(HookEvent event, Hook hook) {
            hooks.add(event, hook);
            return this;
        }

        /** Before every model call, of the main agent and of sub-agents; can deny it (the run ends with an error). */
        public Builder beforeLlmCall(Hook hook) {
            return hook(HookEvent.PRE_LLM, hook);
        }

        /** After every model call (also a failed one: {@code isError}). Observation only. */
        public Builder afterLlmCall(Hook hook) {
            return hook(HookEvent.POST_LLM, hook);
        }

        /** Before every tool call, before the permission check; can deny it or rewrite its input. */
        public Builder beforeToolCall(Hook hook) {
            return hook(HookEvent.PRE_TOOL, hook);
        }

        /** After every tool call; can add context to its result. */
        public Builder afterToolCall(Hook hook) {
            return hook(HookEvent.POST_TOOL, hook);
        }

        /** Before an agent starts: the main agent for a user turn, or a sub-agent; can deny it. */
        public Builder beforeAgent(Hook hook) {
            return hook(HookEvent.PRE_AGENT, hook);
        }

        /** After an agent finished, successfully or not. Observation only. */
        public Builder afterAgent(Hook hook) {
            return hook(HookEvent.POST_AGENT, hook);
        }

        /**
         * Adds a plugin passed in code: it needs no entry under {@code plugins}, unless that entry says
         * {@code "enabled": false}. It is installed at {@link #build()}, with the settings given by then.
         */
        public Builder plugin(HarnessPlugin plugin) {
            explicitPlugins.add(java.util.Objects.requireNonNull(plugin));
            return this;
        }

        private void installPlugins() {
            List<String> warnings = new ArrayList<>();
            List<PluginLoader.Candidate> found = discover
                    ? PluginLoader.discover(pluginDir, pluginDirExplicit, warnings) : List.of();
            List<PluginLoader.Candidate> explicit = new ArrayList<>();
            explicitPlugins.forEach(p -> explicit.add(new PluginLoader.Candidate(p, "code")));
            PluginLoader.Result r = PluginLoader.resolve(found, explicit, settings.plugins());
            warnings.addAll(r.warnings());
            warnings.forEach(w -> LOG.warn(w));
            r.infos().forEach(i -> LOG.info(i));
            if (discover || !explicit.isEmpty()) LOG.info(r.summary());
            for (PluginLoader.Install i : r.install()) {
                i.plugin().install(new PluginRegistrar() {
                    @Override
                    public PluginRegistrar tool(HarnessTool tool) {
                        tools.add(tool);
                        return this;
                    }

                    @Override
                    public PluginRegistrar hook(HookEvent event, Hook hook) {
                        hooks.add(event, hook);
                        return this;
                    }

                    @Override
                    public PluginRegistrar command(String name, String description, CustomCommand command) {
                        commands.add(new CommandSpec(name, description, command));
                        return this;
                    }

                    @Override
                    public PluginRegistrar documentReader(org.kendar.llm.harness.tools.DocumentReader reader) {
                        documentReaders.register(reader);
                        return this;
                    }

                    @Override
                    public PluginRegistrar replaces(String toolName) {
                        removedTools.add(toolName);
                        replacedTools.add(toolName);
                        return this;
                    }

                    @Override
                    public org.kendar.llm.harness.tools.ToolCatalog catalog() {
                        return new org.kendar.llm.harness.tools.ScopeToolCatalog();
                    }

                    @Override
                    public PluginRegistrar toolNameCheck(java.util.function.Consumer<java.util.Collection<String>> check) {
                        nameChecks.add(java.util.Objects.requireNonNull(check));
                        return this;
                    }
                }, i.settings());
            }
            pluginSummary = r;
        }

        /** What {@link #build()} decided about plugins (null before). */
        public PluginLoader.Result pluginResult() {
            return pluginSummary;
        }

        /** Registers a reader that lets {@code read_file} extract text from a binary format. */
        public Builder documentReader(org.kendar.llm.harness.tools.DocumentReader reader) {
            documentReaders.register(reader);
            return this;
        }

        /**
         * At {@link #build()}, loads the {@link HarnessPlugin}s found through {@link java.util.ServiceLoader} on the
         * classpath and in {@link #pluginDir}: only those listed and enabled under {@code plugins} in the settings.
         */
        public Builder discoverPlugins() {
            this.discover = true;
            return this;
        }

        /** Directory of plugin jars for {@link #discoverPlugins()}; a missing directory is a warning. */
        public Builder pluginDir(Path dir) {
            this.pluginDir = dir;
            this.pluginDirExplicit = true;
            return this;
        }

        /** MCP servers and REST APIs to expose as tools; their tools are discovered at {@link #build()}. */
        public Builder settings(HarnessSettings settings) {
            this.settings = settings;
            return this;
        }

        /** Loads the settings file; {@code ${ENV_VAR}} references in it are expanded. */
        public Builder settings(Path file) {
            return settings(HarnessSettings.load(file));
        }

        /** Do not register the built-in Files, Shell and Web tools. */
        public Builder withoutStandardTools() {
            this.standardTools = false;
            return this;
        }

        /** Removes the named standard tools before the registry is built: they are absent, not hidden. */
        public Builder withoutTools(java.util.Collection<String> names) {
            this.removedTools.addAll(names);
            return this;
        }

        public Builder sessionStore(ChatSessionStore store) {
            this.sessionStore = store;
            return this;
        }

        public Builder subAgentRunLog(SubAgentRunLog log) {
            this.subAgentRunLog = log;
            return this;
        }

        public Builder approvalPolicy(ToolApprovalPolicy policy) {
            this.approvalPolicy = policy;
            return this;
        }

        /**
         * Per-call headers for MCP and REST calls, resolved for the chat's principal. Default: none (the headers of
         * the settings file are always sent).
         */
        public Builder credentialResolver(org.kendar.llm.harness.auth.CredentialResolver resolver) {
            this.credentials = resolver;
            return this;
        }

        /**
         * Where OAuth tokens are kept (logins of REST APIs, MCP servers, providers). Wins over the {@code tokenStore}
         * settings. Default: built from the settings ({@link org.kendar.llm.harness.auth.TokenStores}).
         */
        public Builder tokenStore(org.kendar.llm.harness.auth.TokenStore store) {
            this.tokenStore = store;
            return this;
        }

        /** Directory of the file token store when the settings name none (an embedder with users picks its own). */
        public Builder tokenStoreDir(Path dir) {
            this.tokenStoreDir = dir;
            return this;
        }

        /** Tools and approval policy per user, resolved when a call is made. Default: every tool, harness approval. */
        public Builder userPolicyResolver(org.kendar.llm.harness.auth.UserPolicyResolver resolver) {
            this.userPolicies = resolver;
            return this;
        }

        /** Principal id to principal (roles, attributes). Default: the id alone, with no roles. */
        public Builder principalDirectory(java.util.function.Function<String, org.kendar.llm.harness.auth.HarnessPrincipal> directory) {
            this.principalDirectory = directory;
            return this;
        }

        /** Default: {@link LocalDirSandboxExecutor}, which has no isolation. */
        public Builder sandbox(SandboxExecutor sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        public Builder metrics(HarnessMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        /** Prices used by the cost estimate; default: unpriced. */
        public Builder pricing(HarnessPricing pricing) {
            this.pricing = pricing;
            return this;
        }

        public Builder config(HarnessConfig config) {
            this.config = config;
            return this;
        }

        /** Addendum appended to the generic base prompt. */
        public Builder systemPrompt(String addendum) {
            this.systemPrompt = addendum == null ? "" : addendum;
            return this;
        }

        /**
         * Replaces the built-in {@code system-prompt.md}. Drops its tool-usage guidance; the small-model rules, the
         * addendum, instruction files and strategy notes still follow it.
         */
        public Builder systemPromptReplace(String replacement) {
            this.systemPromptReplace = replacement == null || replacement.isBlank() ? null : replacement;
            return this;
        }

        /**
         * The main model is small: adds the stricter rules of {@code small-model.md} after the base prompt, for the
         * main agent and its sub-agents. Also on when settings {@code smallModel} is true.
         */
        public Builder smallModel(boolean small) {
            this.smallModel = small;
            return this;
        }

        /**
         * A custom context strategy; it replaces the one chosen by {@code config.contextStrategy()} (settings
         * {@code context.strategy}: compaction, mnemonic or scope).
         */
        public Builder contextStrategy(ContextStrategyFactory factory) {
            this.contextStrategy = factory;
            return this;
        }

        /** Where the mnemonic strategy keeps its decisions; default in memory. */
        /** Thresholds of the {@code scope} context strategy. */
        public Builder scopeOptions(org.kendar.llm.harness.context.scope.ScopeStrategy.Options options) {
            this.scopeOptions = options;
            return this;
        }

        /** Where tool grants and todo lists are kept; default in memory (lost on restart). */
        public Builder stateStore(org.kendar.llm.harness.state.StateStore store) {
            this.stateStore = store;
            return this;
        }

        public Builder mnemonicStore(MnemonicStore store) {
            this.mnemonicStore = store;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        /**
         * @throws IllegalArgumentException on duplicate tool names
         * @throws IllegalStateException without a model
         * @throws org.kendar.llm.harness.integration.ToolNames.CollisionException when a tool of an MCP server or REST
         *         API enabled at start takes a name already in use (the clients opened so far are closed)
         */
        public AgentHarness build() {
            installPlugins();
            config = settings.applyTo(config);
            org.kendar.llm.harness.llm.ProviderCatalog providers = new org.kendar.llm.harness.llm.ProviderCatalog(
                    settings.providers(), mapper, config.maxTokens());
            if (sessionProvider != null) providers.setCustom(sessionProvider);
            if (modelClient == null && settings.defaultProvider() != null) {
                modelClient = providers.client(settings.defaultProvider());
                activeProvider = settings.defaultProvider();
            }
            if (modelClient == null) throw new IllegalStateException("modelClient is required");
            org.kendar.llm.harness.llm.ProviderSettings active = providers.find(activeProvider);
            org.kendar.llm.harness.llm.ModelSwitch modelSwitch = new org.kendar.llm.harness.llm.ModelSwitch(
                    modelClient, activeProvider, active == null ? null : active.contextTokens());
            modelClient = modelSwitch;
            int maxParallel = settings.maxParallel();
            if (maxParallel > 0) modelClient = new LimitedModelClient(modelClient, maxParallel);
            SandboxExecutor box = sandbox != null ? sandbox : new LocalDirSandboxExecutor(config);
            box.environmentWhitelist(settings.whitelistedEnvVars());
            box.outputLogLimit(settings.sandboxOutputLogMaxBytes());

            org.kendar.llm.harness.tools.Checkpoints harnessCheckpoints = new org.kendar.llm.harness.tools.Checkpoints();
            List<HarnessTool> all = new ArrayList<>();
            List<HarnessTool> fileTools = new ArrayList<>();
            org.kendar.llm.harness.tools.StorageQuota quota =
                    new org.kendar.llm.harness.tools.StorageQuota(settings.storageMaxBytes(), box::chatStorage);
            if (standardTools) {
                fileTools.addAll(new FileSystemAgentTools(documentReaders, harnessCheckpoints, quota,
                        settings.filesMaxReadBytes()).tools());
                all.addAll(fileTools);
                all.addAll(new ShellAgentTools(box, config).tools());
                all.addAll(new WebAgentTools(config, mapper, quota, settings.webFetchMaxBytes()).tools());
            }
            all.removeIf(t -> removedTools.contains(t.name()));
            all.addAll(tools);
            SkillCatalog skills = new SkillCatalog(settings.skills());
            // registered even without skills: a user policy may bring the user's own (dropped per chat when none)
            if (!skills.isEmpty() || userPolicies != null) all.add(new SkillTool(skills));
            HarnessToolRegistry registry = new HarnessToolRegistry(all, approvalPolicy, replacedTools, nameChecks);
            PermissionRules permissionRules = settings.permissionRules();
            registry.hideIf(permissionRules::hides);
            ChatSessionService sessions = new ChatSessionService(sessionStore, config.agentId(), mapper);
            boolean multiUser = userPolicies != null;
            org.kendar.llm.harness.auth.TokenStore tokenStore = this.tokenStore != null ? this.tokenStore
                    : org.kendar.llm.harness.auth.TokenStores.create(settings.tokenStore(), multiUser, tokenStoreDir,
                    System::getenv);
            HttpClient http = HttpClient.newHttpClient();
            org.kendar.llm.harness.auth.OAuthSources oauth = org.kendar.llm.harness.auth.OAuthSources.from(settings,
                    tokenStore, http, mapper, multiUser);
            // the OAuth header wins over the embedder's (settings headers < user file headers < OAuth)
            org.kendar.llm.harness.auth.CredentialResolver resolver = oauth.resolver().isEmpty() ? credentials
                    : org.kendar.llm.harness.auth.CredentialResolver.chain(credentials, oauth.resolver());
            org.kendar.llm.harness.auth.Identity identity = new org.kendar.llm.harness.auth.Identity(resolver,
                    userPolicies, principalDirectory,
                    chatId -> sessions.find(chatId).map(ChatSessionData::principal).orElse(null));
            ExternalSources sources = new ExternalSources(registry, settings, http, mapper, identity, tokenStore,
                    oauth::specHeaders);
            builtOAuth = oauth;
            try {
                AgentHarness h = build(sources, tokenStore, registry, sessions, identity, permissionRules, skills,
                        fileTools, harnessCheckpoints, quota, box, providers, modelSwitch, maxParallel);
                h.oauth = oauth;
                h.multiUser = multiUser;
                return h;
            } catch (RuntimeException | Error e) {
                sources.close(); // the MCP processes started above must not outlive a failed build
                throw e;
            }
        }

        /** The steps of {@link #build()} after the external sources are connected. */
        private AgentHarness build(ExternalSources sources, org.kendar.llm.harness.auth.TokenStore tokenStore,
                                   HarnessToolRegistry registry, ChatSessionService sessions,
                                   org.kendar.llm.harness.auth.Identity identity, PermissionRules permissionRules,
                                   SkillCatalog skills, List<HarnessTool> fileTools,
                                   org.kendar.llm.harness.tools.Checkpoints harnessCheckpoints,
                                   org.kendar.llm.harness.tools.StorageQuota quota, SandboxExecutor box,
                                   org.kendar.llm.harness.llm.ProviderCatalog providers,
                                   org.kendar.llm.harness.llm.ModelSwitch modelSwitch, int maxParallel) {
            ApprovalService approvals = new ApprovalService(config.approvalTimeoutSeconds(), stateStore);
            ModelClient fast = fastClient != null ? fastClient
                    : settings.fastModel() != null ? ModelClients.from(settings.fastModel(), mapper) : null;
            ContextStrategy context = contextStrategy(new ContextStrategyFactory.Deps(config, modelClient,
                    fast != null ? fast : modelClient, approvals, mapper, metrics, subAgentRunLog), box, sessions);
            context.install(hooks);
            ToolSelector selector = new ToolSelector(config, fast != null ? fast : modelClient, mapper);
            RequestToolGroupTool requestToolGroup = new RequestToolGroupTool(registry, sessions, mapper);
            AgentLoop[] ref = new AgentLoop[1];
            SubAgentTool subAgentTool = new SubAgentTool(() -> ref[0], mapper, agentCatalog(settings, mapper, maxParallel, providers),
                    maxParallel != 1);
            String instructions = InstructionLoader.load(settings.instructionFiles());
            String prompt = (systemPromptReplace != null ? systemPromptReplace : basePrompt())
                    + (smallModel || settings.isSmallModel() ? "\n\n" + Prompts.load("small-model.md") : "")
                    + (systemPrompt.isBlank() ? "" : "\n\n" + systemPrompt)
                    + (instructions.isBlank() ? "" : "\n\n" + instructions)
                    + (context.kind() == ContextStrategyKind.MNEMONIC ? "\n\n" + MnemonicStrategy.systemAddendum() : "")
                    + (context.kind() == ContextStrategyKind.SCOPE
                    ? "\n\n" + org.kendar.llm.harness.context.scope.ScopeStrategy.systemAddendum() : "");
            AgentLoop loop = new AgentLoop(config, modelClient, context, selector,
                    new PermissionGate(approvalPolicy, permissionRules), approvals, sessions, box, registry, subAgentTool,
                    requestToolGroup, subAgentRunLog, mapper, metrics, prompt, hooks,
                    fast == null ? null : new ToolClassifier(fast, mapper));
            loop.builtinFileTools(fileTools);
            loop.identity(identity);
            ref[0] = loop;
            // verify-on-stop from the settings: after the hooks added in code and by plugins
            if (!settings.stopHooks().isEmpty()) {
                var sanitizer = new org.kendar.llm.harness.safety.ToolOutputSanitizer(
                        org.kendar.llm.harness.safety.ToolOutputSanitizer.Mode.parse(config.output().sanitize()),
                        config.output().extraPatterns());
                for (HarnessSettings.StopHook h : settings.stopHooks()) {
                    hooks.add(HookEvent.STOP, new org.kendar.llm.harness.hooks.CommandStopHook(h, box,
                            loop::changedInRun, sanitizer));
                }
            }
            if (!inputValidators.isEmpty() || !defaultInputValidator) {
                List<org.kendar.llm.harness.tools.ToolInputValidator> chain = new ArrayList<>();
                if (defaultInputValidator) chain.add(new org.kendar.llm.harness.tools.JsonSchemaToolInputValidator());
                chain.addAll(inputValidators);
                loop.inputValidator(org.kendar.llm.harness.tools.ToolInputValidator.chain(chain));
            }
            if (settings.noiseCommands()
                    && org.kendar.llm.harness.context.noise.NoiseCommandPruner.appliesTo(context.kind())) {
                loop.noisePruner(new org.kendar.llm.harness.context.noise.NoiseCommandPruner(sessions,
                        new org.kendar.llm.harness.context.noise.NoiseQualifier(fast != null ? fast : modelClient),
                        mapper));
            }
            AgentService service = new AgentService(loop, sessions, approvals, mapper, config, maxParallel);
            AgentHarness harness = new AgentHarness(service, sessions, registry, sources, skills, box,
                    subAgentRunLog, context, config, pricing);
            harness.permissionRules = permissionRules;
            harness.providers = providers;
            harness.checkpoints = harnessCheckpoints;
            harness.quota = quota;
            harness.tokenStore = tokenStore;
            service.onTurnStart(harness::beginTurn);
            // whatever path deletes a chat (REST, /chats, retention): drop what is kept for it in memory
            sessions.onDelete(id -> {
                context.forget(id);
                approvals.forget(id);
                loop.forgetTaint(id);
                quota.forget(id);
            });
            // a login started from the web ended: tell the chat it was started from
            harness.commands().onLoginFinished((chatId, msg) -> service.notify(chatId, "login",
                    mapper.createObjectNode().put("message", msg).put("ok", msg != null && msg.startsWith("Logged in"))));
            harness.modelSwitch = modelSwitch;
            harness.maxParallel = maxParallel;
            java.util.Map<String, org.kendar.llm.harness.auth.OAuthLogin> found = new java.util.TreeMap<>();
            for (var l : java.util.ServiceLoader.load(org.kendar.llm.harness.auth.OAuthLogin.class)) found.put(l.name(), l);
            // REST APIs (not service accounts) and MCP servers with oauth: built from the settings, same instances as
            // the resolver's
            found.putAll(builtOAuth.logins());
            harness.logins = found;
            commands.forEach(c -> harness.commands().register(c.name(), c.description(), c.command()));
            return harness;
        }

        private ContextStrategy contextStrategy(ContextStrategyFactory.Deps deps, SandboxExecutor box,
                                                ChatSessionService sessions) {
            if (contextStrategy != null) return contextStrategy.create(deps);
            return switch (deps.config().contextStrategy()) {
                case MNEMONIC -> new MnemonicStrategy(deps.config(), deps.mapper(), deps.metrics(), mnemonicStore,
                        deps.subAgentRuns(),
                        chat -> sessions.find(chat).map(ChatSessionData::agentId).orElse(null),
                        chat -> sessions.find(chat).map(sessions::messages).orElse(null));
                case SCOPE -> new org.kendar.llm.harness.context.scope.ScopeStrategy(deps.config(),
                        new ContextManager(deps.config(), deps.summaryModel(), deps.approvals(), deps.mapper(),
                                deps.metrics()), deps.mapper(),
                        new org.kendar.llm.harness.context.scope.TodoStore(stateStore, deps.mapper()), settings.scopeOptions(scopeOptions),
                        offloadSink(box));
                case COMPACTION, CUSTOM -> new CompactionStrategy(new ContextManager(deps.config(), deps.summaryModel(),
                        deps.approvals(), deps.mapper(), deps.metrics()), deps.config(), deps.mapper());
            };
        }

        /**
         * Offloaded tool results go where the agent's file tools read: through the sandbox's file backend when it has
         * one (a container), else to the scratch directory on the host.
         */
        private static org.kendar.llm.harness.context.scope.ScopeStrategy.OffloadSink offloadSink(SandboxExecutor box) {
            var host = org.kendar.llm.harness.context.scope.ScopeStrategy.OffloadSink.host(
                    (chat, agent) -> box.scratchDir(chat, AgentLoop.scratchId(agent)), box::displayPath);
            return (chat, agent, name, text) -> {
                var files = box.files(chat, AgentLoop.scratchId(agent));
                if (files == null) return host.store(chat, agent, name, text);
                String dir = files.scratchRoot() + "/offload";
                // planMode: the scratch root, writable whatever the mode
                try {
                    files.mkdir(files.writeTarget(dir, true));
                } catch (IOException e) {
                    // it may exist already: the write below tells
                }
                files.write(files.writeTarget(dir + "/" + name, true), text.getBytes(StandardCharsets.UTF_8), false);
                return dir + "/" + name;
            };
        }

        private static AgentCatalog agentCatalog(HarnessSettings settings, ObjectMapper mapper, int maxParallel,
                                                  org.kendar.llm.harness.llm.ProviderCatalog providers) {
            List<AgentDefinition> defs = new ArrayList<>();
            for (AgentSettings a : settings.agents()) {
                String prompt = a.prompt();
                if ((prompt == null || prompt.isBlank()) && a.promptFile() != null) {
                    try {
                        prompt = Files.readString(Path.of(a.promptFile()), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read prompt file of agent " + a.name(), e);
                    }
                }
                ModelClient own = a.provider() != null ? providers.client(a.provider())
                        : a.model() == null ? null : ModelClients.from(a.model(), mapper);
                if (own != null && maxParallel > 0) own = new LimitedModelClient(own, maxParallel);
                defs.add(new AgentDefinition(a.name(), a.description() == null ? "" : a.description(), a.tools(),
                        prompt, Boolean.TRUE.equals(a.inheritContext()), own, Boolean.TRUE.equals(a.readOnly()),
                        a.temperature(), a.thinking()));
            }
            return new AgentCatalog(defs);
        }

        private static String basePrompt() {
            return Prompts.load("system-prompt.md");
        }
    }
}

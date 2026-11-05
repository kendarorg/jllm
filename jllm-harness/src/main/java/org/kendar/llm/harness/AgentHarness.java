package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.agent.AgentCatalog;
import org.kendar.llm.harness.agent.AgentDefinition;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.instructions.InstructionLoader;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.skills.SkillTool;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.agent.AgentLoop;
import org.kendar.llm.harness.agent.AgentService;
import org.kendar.llm.harness.agent.RequestToolGroupTool;
import org.kendar.llm.harness.agent.SubAgentTool;
import org.kendar.llm.harness.agent.ToolSelector;
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
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelClients;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.PermissionGate;
import org.kendar.llm.harness.permission.PermissionRules;
import org.kendar.llm.harness.commands.CustomCommand;
import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.integration.ExternalSources;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.permission.ToolClassifier;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.llm.LimitedModelClient;
import org.kendar.llm.harness.sandbox.LocalDirSandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.ShellAgentTools;
import org.kendar.llm.harness.tools.WebAgentTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Facade of the agent harness: owns one run per chat, persists chats through a {@link ChatSessionStore}, and runs a
 * ReAct loop over the standard tools plus the tools injected by the consumer.
 */
public final class AgentHarness {

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
        if (isRunning(chatId)) throw new AgentService.RunAlreadyActiveException(chatId);
        var files = sandbox.files(chatId, null); // non-null: the files are in a container
        if (files == null) checkpoints.beginTurn(chatId, sandbox.workDir(chatId));
        else checkpoints.beginTurn(chatId, files);
        service.start(chatId, text);
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
        if (files != null) return checkpoints.undo(chatId, files, turns);
        return checkpoints.undo(chatId, sandbox.workDir(chatId), turns);
    }

    /** Releases the sandbox (removes the web containers); a kept CLI container stays. Safe to call twice. */
    public void close() {
        sandbox.close();
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

    public boolean isRunning(String chatId) {
        return service.isRunning(chatId);
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

    /** Manual compaction. @throws AgentService.RunAlreadyActiveException if a run is in progress */
    public CompactResult compact(String chatId, String focus) {
        return service.compact(chatId, focus);
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
        private ModelClient fastClient;
        private ChatSessionStore sessionStore = new InMemoryChatSessionStore();
        private SubAgentRunLog subAgentRunLog = new InMemorySubAgentRunLog();
        private ToolApprovalPolicy approvalPolicy = ToolApprovalPolicy.byKind();
        private SandboxExecutor sandbox;
        private HarnessMetrics metrics = HarnessMetrics.noop();
        private HarnessConfig config = HarnessConfig.defaults();
        private HarnessPricing pricing = new HarnessPricing(0, 0, 0, 0);
        private String systemPrompt = "";
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
        private org.kendar.llm.harness.llm.ProviderSettings sessionProvider;
        private final org.kendar.llm.harness.tools.DocumentReaders documentReaders =
                new org.kendar.llm.harness.tools.DocumentReaders();

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

        /** Installs a plugin: its tools, hooks and commands are added as if registered on this builder. */
        public Builder plugin(HarnessPlugin plugin) {
            plugin.install(new PluginRegistrar() {
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
            });
            return this;
        }

        /** Registers a reader that lets {@code read_file} extract text from a binary format. */
        public Builder documentReader(org.kendar.llm.harness.tools.DocumentReader reader) {
            documentReaders.register(reader);
            return this;
        }

        /** Installs every {@link HarnessPlugin} found on the classpath through {@link java.util.ServiceLoader}. */
        public Builder discoverPlugins() {
            for (HarnessPlugin plugin : java.util.ServiceLoader.load(HarnessPlugin.class)) plugin(plugin);
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

        /** @throws IllegalArgumentException on duplicate tool names; @throws IllegalStateException without a model */
        public AgentHarness build() {
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

            org.kendar.llm.harness.tools.Checkpoints harnessCheckpoints = new org.kendar.llm.harness.tools.Checkpoints();
            List<HarnessTool> all = new ArrayList<>();
            if (standardTools) {
                all.addAll(new FileSystemAgentTools(documentReaders, harnessCheckpoints).tools());
                all.addAll(new ShellAgentTools(box, config).tools());
                all.addAll(new WebAgentTools(config, mapper).tools());
            }
            all.addAll(tools);
            SkillCatalog skills = new SkillCatalog(settings.skills());
            if (!skills.isEmpty()) all.add(new SkillTool(skills));
            HarnessToolRegistry registry = new HarnessToolRegistry(all, approvalPolicy);
            PermissionRules permissionRules = settings.permissionRules();
            registry.hideIf(permissionRules::hides);
            ExternalSources sources = new ExternalSources(registry, settings, HttpClient.newHttpClient(), mapper);

            ChatSessionService sessions = new ChatSessionService(sessionStore, config.agentId(), mapper);
            ApprovalService approvals = new ApprovalService(config.approvalTimeoutSeconds(), stateStore);
            ModelClient fast = fastClient != null ? fastClient
                    : settings.fastModel() != null ? ModelClients.from(settings.fastModel(), mapper) : null;
            ContextStrategy context = contextStrategy(new ContextStrategyFactory.Deps(config, modelClient,
                    fast != null ? fast : modelClient, approvals, mapper, metrics, subAgentRunLog), box);
            context.install(hooks);
            ToolSelector selector = new ToolSelector(config, fast != null ? fast : modelClient, mapper);
            RequestToolGroupTool requestToolGroup = new RequestToolGroupTool(registry, sessions, mapper);
            AgentLoop[] ref = new AgentLoop[1];
            SubAgentTool subAgentTool = new SubAgentTool(() -> ref[0], mapper, agentCatalog(settings, mapper, maxParallel, providers),
                    maxParallel != 1);
            String instructions = InstructionLoader.load(settings.instructionFiles());
            String prompt = basePrompt()
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
            ref[0] = loop;
            AgentService service = new AgentService(loop, sessions, approvals, mapper, config, maxParallel);
            AgentHarness harness = new AgentHarness(service, sessions, registry, sources, skills, box,
                    subAgentRunLog, context, config, pricing);
            harness.permissionRules = permissionRules;
            harness.providers = providers;
            harness.checkpoints = harnessCheckpoints;
            harness.modelSwitch = modelSwitch;
            harness.maxParallel = maxParallel;
            commands.forEach(c -> harness.commands().register(c.name(), c.description(), c.command()));
            return harness;
        }

        private ContextStrategy contextStrategy(ContextStrategyFactory.Deps deps, SandboxExecutor box) {
            if (contextStrategy != null) return contextStrategy.create(deps);
            return switch (deps.config().contextStrategy()) {
                case MNEMONIC -> new MnemonicStrategy(deps.config(), deps.mapper(), deps.metrics(), mnemonicStore,
                        deps.subAgentRuns());
                case SCOPE -> new org.kendar.llm.harness.context.scope.ScopeStrategy(deps.config(),
                        new ContextManager(deps.config(), deps.summaryModel(), deps.approvals(), deps.mapper(),
                                deps.metrics()), deps.mapper(),
                        new org.kendar.llm.harness.context.scope.TodoStore(stateStore, deps.mapper()), settings.scopeOptions(scopeOptions),
                        (chat, agent) -> box.scratchDir(chat, AgentLoop.scratchId(agent)), box::displayPath);
                case COMPACTION, CUSTOM -> new CompactionStrategy(new ContextManager(deps.config(), deps.summaryModel(),
                        deps.approvals(), deps.mapper(), deps.metrics()), deps.config(), deps.mapper());
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
                        a.temperature()));
            }
            return new AgentCatalog(defs);
        }

        private static String basePrompt() {
            return Prompts.load("system-prompt.md");
        }
    }
}

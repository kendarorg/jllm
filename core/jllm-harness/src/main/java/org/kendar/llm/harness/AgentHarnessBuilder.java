package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.harness.context.ContextStrategyFactory;
import org.kendar.llm.harness.context.mnemonic.InMemoryMnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.context.scope.ScopeStrategy;
import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.integration.ExternalSources;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginLoader;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.safety.ContentScanner;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.kendar.llm.harness.sandbox.LocalSandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.settings.HarnessConfigMapper;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.state.InMemoryStateStore;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.harness.tools.DocumentReader;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolInputValidator;
import org.kendar.llm.harness.integration.ToolNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Collects the options of an {@link AgentHarness} and assembles it: plugins, tools, auth, context strategy, system
 * prompt and agent loop are each built by their own assembler.
 */
public final class AgentHarnessBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(AgentHarnessBuilder.class);

    private ModelClient modelClient;
    private boolean standardTools = true;
    private ModelClient fastClient;
    private ChatSessionStore sessionStore = new InMemoryChatSessionStore();
    private SubAgentRunLog subAgentRunLog = new InMemorySubAgentRunLog();
    private ToolApprovalPolicy approvalPolicy = ToolApprovalPolicy.byKind();
    private CredentialResolver credentials;
    private TokenStore tokenStore;
    private Path tokenStoreDir;
    private UserPolicyResolver userPolicies;
    private Function<String, HarnessPrincipal> principalDirectory;
    private SandboxExecutor sandbox;
    private HarnessMetrics metrics = HarnessMetrics.noop();
    private HarnessConfig config = HarnessConfig.defaults();
    private HarnessPricing pricing = new HarnessPricing(0, 0, 0, 0);
    private String systemPrompt = "";
    private String systemPromptReplace;
    private boolean smallModel;
    private ObjectMapper mapper = new ObjectMapper();
    private HarnessSettings settings = HarnessSettings.empty();
    /** The file {@link #settings(Path)} read: scanned at {@link #build()}. */
    private Path settingsFile;
    private ContentWarnings contentWarnings = new ContentWarnings();
    private ContextStrategyFactory contextStrategy;
    private MnemonicStore mnemonicStore = new InMemoryMnemonicStore();
    private ScopeStrategy.Options scopeOptions = ScopeStrategy.Options.defaults();
    private StateStore stateStore = new InMemoryStateStore();
    private String activeProvider;
    private final List<ToolInputValidator> inputValidators = new ArrayList<>();
    private boolean defaultInputValidator = true;
    private ProviderSettings sessionProvider;
    private final PluginInstaller plugins = new PluginInstaller();
    private final List<HarnessPlugin> explicitPlugins = new ArrayList<>();
    private boolean discover;
    private Path pluginDir;
    private boolean pluginDirExplicit;
    private PluginLoader.Result pluginSummary;

    public AgentHarnessBuilder() {
    }

    /** Small model for the auto-mode classifier, summaries and tool-group selection; default: settings' fastModel. */
    public AgentHarnessBuilder fastModelClient(ModelClient client) {
        this.fastClient = client;
        return this;
    }

    /** Names the provider the injected {@link #modelClient} comes from, for {@code /model}. */
    public AgentHarnessBuilder activeProvider(String name) {
        this.activeProvider = name;
        return this;
    }

    /** A provider that exists for this session only (the {@code custom} entry of {@code /model}). */
    public AgentHarnessBuilder sessionProvider(ProviderSettings provider) {
        this.sessionProvider = provider;
        return this;
    }

    public AgentHarnessBuilder modelClient(ModelClient client) {
        this.modelClient = client;
        return this;
    }

    /** Tools added to the standard ones. May be called more than once. */
    public AgentHarnessBuilder tools(List<? extends HarnessTool> tools) {
        plugins.tools.addAll(tools);
        return this;
    }

    /**
     * Replaces the default (JSON Schema) check of tool-call arguments, and any validator added before.
     * It decides what each {@link org.kendar.llm.harness.tools.ValidationMode} means for it.
     */
    public AgentHarnessBuilder toolInputValidator(ToolInputValidator validator) {
        this.inputValidators.clear();
        this.inputValidators.add(Objects.requireNonNull(validator));
        this.defaultInputValidator = false;
        return this;
    }

    /** Adds a check that runs after the default one and sees the input as repaired by it. */
    public AgentHarnessBuilder addToolInputValidator(ToolInputValidator validator) {
        this.inputValidators.add(Objects.requireNonNull(validator));
        return this;
    }

    /** Runs {@code hook} on every {@code event}, after the hooks added before it. */
    public AgentHarnessBuilder hook(HookEvent event, Hook hook) {
        plugins.hooks.add(event, hook);
        return this;
    }

    /** Before every model call, of the main agent and of sub-agents; can deny it (the run ends with an error). */
    public AgentHarnessBuilder beforeLlmCall(Hook hook) {
        return hook(HookEvent.PRE_LLM, hook);
    }

    /** After every model call (also a failed one: {@code isError}). Observation only. */
    public AgentHarnessBuilder afterLlmCall(Hook hook) {
        return hook(HookEvent.POST_LLM, hook);
    }

    /** Before every tool call, before the permission check; can deny it or rewrite its input. */
    public AgentHarnessBuilder beforeToolCall(Hook hook) {
        return hook(HookEvent.PRE_TOOL, hook);
    }

    /** After every tool call; can add context to its result. */
    public AgentHarnessBuilder afterToolCall(Hook hook) {
        return hook(HookEvent.POST_TOOL, hook);
    }

    /** Before an agent starts: the main agent for a user turn, or a sub-agent; can deny it. */
    public AgentHarnessBuilder beforeAgent(Hook hook) {
        return hook(HookEvent.PRE_AGENT, hook);
    }

    /** After an agent finished, successfully or not. Observation only. */
    public AgentHarnessBuilder afterAgent(Hook hook) {
        return hook(HookEvent.POST_AGENT, hook);
    }

    /** The services published by plugins and the host (see {@code PluginRegistrar.services()}). */
    public AgentHarnessBuilder services(ServiceRegistry services) {
        plugins.services = Objects.requireNonNull(services);
        return this;
    }

    /**
     * Adds a plugin passed in code: it needs no entry under {@code plugins}, unless that entry says
     * {@code "enabled": false}. It is installed at {@link #build()}, with the settings given by then.
     */
    public AgentHarnessBuilder plugin(HarnessPlugin plugin) {
        explicitPlugins.add(Objects.requireNonNull(plugin));
        return this;
    }

    /** What {@link #build()} decided about plugins (null before). */
    public PluginLoader.Result pluginResult() {
        return pluginSummary;
    }

    /** Registers a reader that lets {@code read_file} extract text from a binary format. */
    public AgentHarnessBuilder documentReader(DocumentReader reader) {
        plugins.documentReaders.register(reader);
        return this;
    }

    /**
     * At {@link #build()}, loads the {@link HarnessPlugin}s found through {@link java.util.ServiceLoader} on the
     * classpath and in {@link #pluginDir}: only those listed and enabled under {@code plugins} in the settings.
     */
    public AgentHarnessBuilder discoverPlugins() {
        this.discover = true;
        return this;
    }

    /** Directory of plugin jars for {@link #discoverPlugins()}; a missing directory is a warning. */
    public AgentHarnessBuilder pluginDir(Path dir) {
        this.pluginDir = dir;
        this.pluginDirExplicit = true;
        return this;
    }

    /** MCP servers and REST APIs to expose as tools; their tools are discovered at {@link #build()}. */
    public AgentHarnessBuilder settings(HarnessSettings settings) {
        this.settings = settings;
        this.settingsFile = null;
        return this;
    }

    /** Loads the settings file; {@code ${ENV_VAR}} references in it are expanded. */
    public AgentHarnessBuilder settings(Path file) {
        settings(HarnessSettingsLoader.load(file));
        this.settingsFile = file;
        return this;
    }

    /**
     * Where suspicious content found in the loaded files is reported (default: a new one). Pass the instance given
     * to {@link HarnessSettings#loadLayered} so the settings files are listed too; see
     * {@link AgentHarness#contentWarnings()}.
     */
    public AgentHarnessBuilder contentWarnings(ContentWarnings warnings) {
        this.contentWarnings = Objects.requireNonNull(warnings);
        return this;
    }

    /** Do not register the built-in Files, Shell and Web tools. */
    public AgentHarnessBuilder withoutStandardTools() {
        this.standardTools = false;
        return this;
    }

    /** Removes the named standard tools before the registry is built: they are absent, not hidden. */
    public AgentHarnessBuilder withoutTools(Collection<String> names) {
        plugins.removedTools.addAll(names);
        return this;
    }

    public AgentHarnessBuilder sessionStore(ChatSessionStore store) {
        this.sessionStore = store;
        return this;
    }

    public AgentHarnessBuilder subAgentRunLog(SubAgentRunLog log) {
        this.subAgentRunLog = log;
        return this;
    }

    public AgentHarnessBuilder approvalPolicy(ToolApprovalPolicy policy) {
        this.approvalPolicy = policy;
        return this;
    }

    /**
     * Per-call headers for MCP and REST calls, resolved for the chat's principal. Default: none (the headers of
     * the settings file are always sent).
     */
    public AgentHarnessBuilder credentialResolver(CredentialResolver resolver) {
        this.credentials = resolver;
        return this;
    }

    /**
     * Where OAuth tokens are kept (logins of REST APIs, MCP servers, providers). Wins over the {@code tokenStore}
     * settings. Default: built from the settings ({@link org.kendar.llm.harness.auth.TokenStores}).
     */
    public AgentHarnessBuilder tokenStore(TokenStore store) {
        this.tokenStore = store;
        return this;
    }

    /** Directory of the file token store when the settings name none (an embedder with users picks its own). */
    public AgentHarnessBuilder tokenStoreDir(Path dir) {
        this.tokenStoreDir = dir;
        return this;
    }

    /** Tools and approval policy per user, resolved when a call is made. Default: every tool, harness approval. */
    public AgentHarnessBuilder userPolicyResolver(UserPolicyResolver resolver) {
        this.userPolicies = resolver;
        return this;
    }

    /** Principal id to principal (roles, attributes). Default: the id alone, with no roles. */
    public AgentHarnessBuilder principalDirectory(Function<String, HarnessPrincipal> directory) {
        this.principalDirectory = directory;
        return this;
    }

    /** Default: {@link LocalSandboxExecutor}, which has no isolation. */
    public AgentHarnessBuilder sandbox(SandboxExecutor sandbox) {
        this.sandbox = sandbox;
        return this;
    }

    public AgentHarnessBuilder metrics(HarnessMetrics metrics) {
        this.metrics = metrics;
        return this;
    }

    /** Prices used by the cost estimate; default: unpriced. */
    public AgentHarnessBuilder pricing(HarnessPricing pricing) {
        this.pricing = pricing;
        return this;
    }

    public AgentHarnessBuilder config(HarnessConfig config) {
        this.config = config;
        return this;
    }

    /** Addendum appended to the generic base prompt. */
    public AgentHarnessBuilder systemPrompt(String addendum) {
        this.systemPrompt = addendum == null ? "" : addendum;
        return this;
    }

    /**
     * Replaces the built-in {@code system-prompt.md}. Drops its tool-usage guidance; the small-model rules, the
     * addendum, instruction files and strategy notes still follow it.
     */
    public AgentHarnessBuilder systemPromptReplace(String replacement) {
        this.systemPromptReplace = replacement == null || replacement.isBlank() ? null : replacement;
        return this;
    }

    /**
     * The main model is small: adds the stricter rules of {@code small-model.md} after the base prompt, for the
     * main agent and its sub-agents. Also on when settings {@code smallModel} is true.
     */
    public AgentHarnessBuilder smallModel(boolean small) {
        this.smallModel = small;
        return this;
    }

    /**
     * A custom context strategy; it replaces the one chosen by {@code config.contextStrategy()} (settings
     * {@code context.strategy}: compaction, mnemonic or scope).
     */
    public AgentHarnessBuilder contextStrategy(ContextStrategyFactory factory) {
        this.contextStrategy = factory;
        return this;
    }

    /** Thresholds of the {@code scope} context strategy. */
    public AgentHarnessBuilder scopeOptions(ScopeStrategy.Options options) {
        this.scopeOptions = options;
        return this;
    }

    /** Where tool grants and todo lists are kept; default in memory (lost on restart). */
    public AgentHarnessBuilder stateStore(StateStore store) {
        this.stateStore = store;
        return this;
    }

    /** Where the mnemonic strategy keeps its decisions; default in memory. */
    public AgentHarnessBuilder mnemonicStore(MnemonicStore store) {
        this.mnemonicStore = store;
        return this;
    }

    public AgentHarnessBuilder objectMapper(ObjectMapper mapper) {
        this.mapper = mapper;
        return this;
    }

    /**
     * @throws IllegalArgumentException on duplicate tool names
     * @throws IllegalStateException without a model
     * @throws ToolNames.CollisionException when a tool of an MCP server or REST API enabled at start takes a name
     *         already in use (the clients opened so far are closed)
     */
    public AgentHarness build() {
        pluginSummary = plugins.install(explicitPlugins, discover, pluginDir, pluginDirExplicit, settings);
        if (settingsFile != null) scanSettingsFile(settingsFile);
        HarnessConfig cfg = HarnessConfigMapper.apply(settings, config);
        ModelAssembler.Models models = ModelAssembler.assemble(settings, cfg, mapper, modelClient, activeProvider,
                sessionProvider);
        SandboxExecutor box = sandbox != null ? sandbox : LocalSandboxExecutor.of(cfg);
        box.environmentWhitelist(settings.whitelistedEnvVars());
        box.outputLogLimit(settings.sandboxOutputLogMaxBytes());
        box.shell(settings.sandboxShell()); // before the bash tool reads its description
        ToolAssembler.Tools tools = ToolAssembler.assemble(settings, cfg, mapper, box, standardTools, plugins,
                approvalPolicy, contentWarnings, userPolicies != null);
        ChatSessionService sessions = new ChatSessionService(sessionStore, cfg.agentId(), mapper);
        AuthAssembler.Auth auth = AuthAssembler.assemble(settings, tokenStore, tokenStoreDir, credentials,
                userPolicies, principalDirectory, mapper, sessions);
        ExternalSources sources = new ExternalSources(tools.registry(), settings, auth.http(), mapper,
                auth.identity(), auth.tokenStore(), auth.oauth()::specHeaders);
        try {
            LoopAssembler.Wired wired = LoopAssembler.assemble(new LoopAssembler.Spec(settings, cfg, mapper, metrics,
                    plugins.hooks, fastClient, subAgentRunLog, approvalPolicy, contentWarnings, plugins.services,
                    stateStore, contextStrategy, mnemonicStore, scopeOptions, systemPrompt, systemPromptReplace,
                    smallModel, inputValidators, defaultInputValidator, models, box, sessions, tools,
                    auth.identity()));
            HarnessAuth facade = new HarnessAuth(auth.tokenStore(), auth.oauth(), auth.multiUser(),
                    AuthAssembler.logins(auth.oauth()), sources, wired.service());
            AgentHarness harness = new AgentHarness(new HarnessParts(wired.service(), sessions, tools.registry(),
                    sources, tools.skills(), box, subAgentRunLog, wired.context(), cfg, pricing,
                    tools.permissionRules(), contentWarnings, plugins.services, models.providers(),
                    models.modelSwitch(), models.maxParallel(), tools.checkpoints(), tools.quota(), facade,
                    plugins.hooks));
            plugins.commands.forEach(c -> harness.commands().register(c.name(), c.description(), c.command()));
            return harness;
        } catch (RuntimeException | Error e) { // boundary: close the MCP processes, rethrown unchanged
            sources.close(); // the MCP processes started above must not outlive a failed build
            throw e;
        }
    }

    /** The free text of a settings file read by {@link #settings(Path)}; an unreadable file was refused already. */
    private void scanSettingsFile(Path file) {
        try {
            contentWarnings.report(file.toString(), ContentScanner.scanSettings(file.toString(),
                    mapper.readTree(file.toFile())));
        } catch (IOException | RuntimeException e) { // boundary: one unreadable settings file must not stop the build
            LOG.warn("Cannot scan settings {}: {}", file, e.getMessage());
        }
    }
}

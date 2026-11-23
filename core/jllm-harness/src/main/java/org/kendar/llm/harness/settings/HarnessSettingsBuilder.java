package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;

import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.llm.LlmSettings;
import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.tools.ExternalLinks;

import java.util.List;

/**
 * Builds a {@link HarnessSettings}: one setter per component, unset ones stay at their defaults. Start from
 * {@link HarnessSettings#builder()} or {@link HarnessSettings#toBuilder()}. The command line flags that replace one
 * nested value ({@link #sandboxBackend}, {@link #dockerImage}, {@link #dockerMode}, {@link #dockerRemote},
 * {@link #maxTokens}, {@link #contextStrategy}) keep the rest of that section.
 */
public final class HarnessSettingsBuilder {
    private List<McpServerSettings> mcpServers;
    private List<RestApiSettings> restApis;
    private List<SkillSettings> skills;
    private List<String> instructionFiles;
    private List<AgentSettings> agents;
    private ExecutionSettings execution;
    private SandboxSettings sandbox;
    private ContextSettings context;
    private LlmSettings fastModel;
    private OutputSettings output;
    private Boolean smallModel;
    private PermissionSettings permissions;
    private List<ProviderSettings> providers;
    private String defaultProvider;
    private Integer chatRetentionLimit;
    private JsonNode plugins;
    private StorageSettings storage;
    private List<String> whitelistedEnvVars;
    private List<String> toolsWhitelistedEnvVars;
    private FileLimits files;
    private WebLimits web;
    private LoopSettings loop;
    private HookSettings hooks;
    private TokenStoreSettings tokenStore;

    HarnessSettingsBuilder() {
    }

    HarnessSettingsBuilder(HarnessSettings s) {
        mcpServers = s.mcpServers();
        restApis = s.restApis();
        skills = s.skills();
        instructionFiles = s.instructionFiles();
        agents = s.agents();
        execution = s.execution();
        sandbox = s.sandbox();
        context = s.context();
        fastModel = s.fastModel();
        output = s.output();
        smallModel = s.smallModel();
        permissions = s.permissions();
        providers = s.providers();
        defaultProvider = s.defaultProvider();
        chatRetentionLimit = s.chatRetentionLimit();
        plugins = s.plugins();
        storage = s.storage();
        whitelistedEnvVars = s.whitelistedEnvVars();
        toolsWhitelistedEnvVars = s.toolsWhitelistedEnvVars();
        files = s.files();
        web = s.web();
        loop = s.loop();
        hooks = s.hooks();
        tokenStore = s.tokenStore();
    }

    public HarnessSettingsBuilder mcpServers(List<McpServerSettings> v) { mcpServers = v; return this; }
    public HarnessSettingsBuilder restApis(List<RestApiSettings> v) { restApis = v; return this; }
    public HarnessSettingsBuilder skills(List<SkillSettings> v) { skills = v; return this; }
    public HarnessSettingsBuilder instructionFiles(List<String> v) { instructionFiles = v; return this; }
    public HarnessSettingsBuilder agents(List<AgentSettings> v) { agents = v; return this; }
    public HarnessSettingsBuilder execution(ExecutionSettings v) { execution = v; return this; }
    public HarnessSettingsBuilder sandbox(SandboxSettings v) { sandbox = v; return this; }
    public HarnessSettingsBuilder context(ContextSettings v) { context = v; return this; }
    public HarnessSettingsBuilder fastModel(LlmSettings v) { fastModel = v; return this; }
    public HarnessSettingsBuilder output(OutputSettings v) { output = v; return this; }
    public HarnessSettingsBuilder smallModel(Boolean v) { smallModel = v; return this; }
    public HarnessSettingsBuilder permissions(PermissionSettings v) { permissions = v; return this; }
    public HarnessSettingsBuilder providers(List<ProviderSettings> v) { providers = v; return this; }
    public HarnessSettingsBuilder defaultProvider(String v) { defaultProvider = v; return this; }
    public HarnessSettingsBuilder chatRetentionLimit(Integer v) { chatRetentionLimit = v; return this; }
    public HarnessSettingsBuilder plugins(JsonNode v) { plugins = v; return this; }
    public HarnessSettingsBuilder storage(StorageSettings v) { storage = v; return this; }
    public HarnessSettingsBuilder whitelistedEnvVars(List<String> v) { whitelistedEnvVars = v; return this; }
    public HarnessSettingsBuilder toolsWhitelistedEnvVars(List<String> v) { toolsWhitelistedEnvVars = v; return this; }
    public HarnessSettingsBuilder files(FileLimits v) { files = v; return this; }
    public HarnessSettingsBuilder web(WebLimits v) { web = v; return this; }
    public HarnessSettingsBuilder loop(LoopSettings v) { loop = v; return this; }
    public HarnessSettingsBuilder hooks(HookSettings v) { hooks = v; return this; }
    public HarnessSettingsBuilder tokenStore(TokenStoreSettings v) { tokenStore = v; return this; }

    /** Replaces {@code files.externalLinks} (a command line flag; the web host forces none). */
    public HarnessSettingsBuilder externalLinks(ExternalLinks v) {
        FileLimits f = files;
        files = new FileLimits(f == null ? null : f.maxReadBytes(), f == null ? null : f.scopedInstructions(),
                f == null ? null : f.scopedInstructionsDepth(), v);
        return this;
    }

    /** Replaces {@code sandbox.backend} (e.g. by a command line flag). */
    public HarnessSettingsBuilder sandboxBackend(String backend) {
        if (!"local".equalsIgnoreCase(backend) && !"docker".equalsIgnoreCase(backend)) {
            throw new IllegalArgumentException("Unknown sandbox backend '" + backend + "' (local or docker)");
        }
        SandboxSettings s = currentSandbox();
        sandbox = new SandboxSettings(s.tempDir(), s.timeoutSeconds(), backend.toLowerCase(), s.localFallback(),
                s.docker(), s.outputLogMaxBytes(), s.shell());
        return this;
    }

    /** Replaces {@code sandbox.docker.image}. */
    public HarnessSettingsBuilder dockerImage(String image) {
        DockerSettings d = currentDocker();
        return docker(new DockerSettings(d.binary(), image, d.memory(), d.cpus(), d.pidsLimit(), d.user(),
                d.network(), d.mode(), d.remote(), d.remoteHost(),
                d.allowInsecure(), d.trustStore(), d.trustStorePassword()));
    }

    /** Replaces {@code sandbox.docker.mode}. */
    public HarnessSettingsBuilder dockerMode(String mode) {
        if (!"oneshot".equalsIgnoreCase(mode) && !"daemon".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Unknown docker mode '" + mode + "' (oneshot or daemon)");
        }
        DockerSettings d = currentDocker();
        return docker(new DockerSettings(d.binary(), d.image(), d.memory(), d.cpus(), d.pidsLimit(), d.user(),
                d.network(), mode.toLowerCase(), d.remote(), d.remoteHost(),
                d.allowInsecure(), d.trustStore(), d.trustStorePassword()));
    }

    /** Replaces {@code sandbox.docker.remote} and {@code remoteHost}; a null argument keeps the current value. */
    public HarnessSettingsBuilder dockerRemote(Boolean remote, String remoteHost) {
        DockerSettings d = currentDocker();
        return docker(new DockerSettings(d.binary(), d.image(), d.memory(), d.cpus(), d.pidsLimit(), d.user(),
                d.network(), d.mode(), remote != null ? remote : d.remote(),
                remoteHost != null ? remoteHost : d.remoteHost(),
                d.allowInsecure(), d.trustStore(), d.trustStorePassword()));
    }

    /** Replaces {@code context.maxTokens}. */
    public HarnessSettingsBuilder maxTokens(int maxTokens) {
        ContextSettings c = currentContext();
        context = new ContextSettings(maxTokens, c.keepRecentTurns(), c.auto(), c.keepTokens(), c.buffer(),
                c.strategy(), c.mnemonic(), c.scope(), c.noiseCommands(), c.deferCompactionOnThinkingOverflow(),
                c.todos());
        return this;
    }

    /** Replaces {@code context.strategy}. @throws IllegalArgumentException for an unknown strategy */
    public HarnessSettingsBuilder contextStrategy(String strategy) {
        ContextStrategyKind.parse(strategy);
        ContextSettings c = currentContext();
        context = new ContextSettings(c.maxTokens(), c.keepRecentTurns(), c.auto(), c.keepTokens(), c.buffer(),
                strategy, c.mnemonic(), c.scope(), c.noiseCommands(), c.deferCompactionOnThinkingOverflow(),
                c.todos());
        return this;
    }

    public HarnessSettings build() {
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context,
                fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins,
                storage, whitelistedEnvVars, toolsWhitelistedEnvVars, files, web, loop, hooks, tokenStore);
    }

    private HarnessSettingsBuilder docker(DockerSettings d) {
        SandboxSettings s = currentSandbox();
        sandbox = new SandboxSettings(s.tempDir(), s.timeoutSeconds(), s.backend(), s.localFallback(), d,
                s.outputLogMaxBytes(), s.shell());
        return this;
    }

    private SandboxSettings currentSandbox() {
        return sandbox == null ? new SandboxSettings(null, null, null, null, null, null, null) : sandbox;
    }

    private DockerSettings currentDocker() {
        DockerSettings d = currentSandbox().docker();
        return d == null ? new DockerSettings(null, null, null, null, null, null, null, null, null, null, null, null, null) : d;
    }

    private ContextSettings currentContext() {
        return context == null ? new ContextSettings(null, null, null, null, null, null, null, null, null, null, null)
                : context;
    }
}

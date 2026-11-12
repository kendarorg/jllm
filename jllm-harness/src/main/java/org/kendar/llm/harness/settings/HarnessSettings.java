package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.scope.ScopeStrategy;
import org.kendar.llm.harness.llm.LlmSettings;
import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.permission.PermissionRules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings file of a harness. String values may use {@code ${ENV_VAR}}.
 *
 * @param instructionFiles files (e.g. KENDAR.md) added to the system prompt, in order; missing files are skipped
 * @param execution        optional concurrency settings
 * @param sandbox          optional scratch directory and shell timeout
 * @param context          optional context window and compaction settings
 * @param fastModel        optional small model for the auto-mode classifier, summaries and tool-group selection
 * @param smallModel       the main model is small: the system prompt gets the stricter small-model rules
 *                         (default false)
 * @param plugins          raw {@code plugins} section: {@code <id>: {enabled, settings}}; only listed and enabled
 *                         plugins found by discovery are loaded
 * @param chatRetentionLimit maximum number of saved chats; at startup the oldest beyond it are deleted. Null or
 *                         absent = unlimited (default)
 */
public record HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                              List<SkillSettings> skills, List<String> instructionFiles,
                              List<AgentSettings> agents, Execution execution, Sandbox sandbox, Context context,
                              LlmSettings fastModel, Output output, Boolean smallModel, Permissions permissions,
                              List<ProviderSettings> providers, String defaultProvider,
                              Integer chatRetentionLimit, JsonNode plugins, Storage storage,
                              List<String> whitelistedEnvVars, List<String> toolsWhitelistedEnvVars) {

    /**
     * The 17 components before the environment whitelists.
     */
    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles,
                           List<AgentSettings> agents, Execution execution, Sandbox sandbox, Context context,
                           LlmSettings fastModel, Output output, Boolean smallModel, Permissions permissions,
                           List<ProviderSettings> providers, String defaultProvider,
                           Integer chatRetentionLimit, JsonNode plugins, Storage storage) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context, fastModel, output,
                smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, null, null);
    }

    /** The 16 components before {@code storage}: no limit. */
    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles,
                           List<AgentSettings> agents, Execution execution, Sandbox sandbox, Context context,
                           LlmSettings fastModel, Output output, Boolean smallModel, Permissions permissions,
                           List<ProviderSettings> providers, String defaultProvider,
                           Integer chatRetentionLimit, JsonNode plugins) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context, fastModel, output,
                smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, null, null, null);
    }

    /** @param maxBytes cap of the total size of a chat's storage; null or -1 = no limit (default) */
    public record Storage(Long maxBytes) {
    }

    /*
     * whitelistedEnvVars:      environment variables of the harness process that shell commands (bash) may inherit;
     *                          patterns with * (any run of characters, case-insensitive: "*key*", "PATH"); default
     *                          ["*"] = all, [] = none
     * toolsWhitelistedEnvVars: the same for the processes of tools (MCP stdio servers); the {@code env} of a server
     *                          in the settings is always added on top
     */

    /** The storage cap in bytes; -1 = no limit. */
    public long storageMaxBytes() {
        return storage == null || storage.maxBytes() == null ? -1 : storage.maxBytes();
    }

    /** The 15 components before {@code plugins}: none listed. */
    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles,
                           List<AgentSettings> agents, Execution execution, Sandbox sandbox, Context context,
                           LlmSettings fastModel, Output output, Boolean smallModel, Permissions permissions,
                           List<ProviderSettings> providers, String defaultProvider,
                           Integer chatRetentionLimit) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context, fastModel, output,
                smallModel, permissions, providers, defaultProvider, chatRetentionLimit, null);
    }

    /** Regex rules, see {@link PermissionRules}. Evaluated deny, ask, allow; settings only. */
    public record Permissions(List<String> deny, List<String> ask, List<String> allow) {
        public Permissions {
            deny = deny == null ? List.of() : List.copyOf(deny);
            ask = ask == null ? List.of() : List.copyOf(ask);
            allow = allow == null ? List.of() : List.copyOf(allow);
        }
    }

    /** The 11 original components; no permissions or providers. */
    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles,
                           List<AgentSettings> agents, Execution execution, Sandbox sandbox, Context context,
                           LlmSettings fastModel, Output output, Boolean smallModel) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context, fastModel, output,
                smallModel, null, null, null, null);
    }

    /** The 14 components before {@code chatRetentionLimit}: unlimited chats. */
    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles,
                           List<AgentSettings> agents, Execution execution, Sandbox sandbox, Context context,
                           LlmSettings fastModel, Output output, Boolean smallModel, Permissions permissions,
                           List<ProviderSettings> providers, String defaultProvider) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context, fastModel, output,
                smallModel, permissions, providers, defaultProvider, null);
    }

    /**
     * Same settings with {@code plugins.<id>.enabled} replaced (e.g. by {@code --plugin}); an id with no entry gets
     * one. The other keys of the entry are kept.
     */
    public HarnessSettings withPlugin(String id, boolean enabled) {
        return withPlugin(id, Boolean.valueOf(enabled), null);
    }

    /** As {@link #withPlugin(String, boolean)}; a null {@code enabled} or {@code settings} keeps the current value. */
    public HarnessSettings withPlugin(String id, Boolean enabled, JsonNode pluginSettings) {
        ObjectNode copy = plugins != null && plugins.isObject() ? ((ObjectNode) plugins).deepCopy()
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode entry = copy.get(id);
        ObjectNode e = entry instanceof ObjectNode o ? o : copy.putObject(id);
        if (enabled != null) e.put("enabled", enabled.booleanValue());
        if (pluginSettings != null) e.set("settings", merge(e.has("settings") ? e.get("settings") : pluginSettings,
                pluginSettings));
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context,
                fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, copy, storage,
                whitelistedEnvVars, toolsWhitelistedEnvVars);
    }

    /**
     * Applies one command line option {@code plugins.<id>.enabled=true} or {@code plugins.<id>.settings.<a.b>=value}
     * (key given without the {@code plugins.} prefix). Numbers and booleans are typed, the rest stays text.
     */
    public HarnessSettings withPluginOption(String key, String value) {
        String[] k = key.split("\\.");
        if (k.length < 2 || k[0].isEmpty()) throw new IllegalArgumentException("Bad plugin option plugins." + key);
        if (k.length == 2 && k[1].equals("enabled")) {
            return withPlugin(k[0], Boolean.parseBoolean(value.isBlank() ? "true" : value), null);
        }
        if (k.length < 3 || !k[1].equals("settings")) {
            throw new IllegalArgumentException("Unknown plugin option plugins." + key
                    + " (use plugins.<id>.enabled or plugins.<id>.settings.<key>)");
        }
        var f = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        JsonNode typed;
        if (value.equals("true") || value.equals("false")) typed = f.booleanNode(Boolean.parseBoolean(value));
        else if (value.matches("-?\\d+")) typed = f.numberNode(Long.parseLong(value));
        else if (value.matches("-?\\d*\\.\\d+")) typed = f.numberNode(Double.parseDouble(value));
        else typed = f.textNode(value);
        ObjectNode leaf = f.objectNode();
        ObjectNode at = leaf;
        for (int i = 2; i < k.length - 1; i++) at = at.putObject(k[i]);
        at.set(k[k.length - 1], typed);
        return withPlugin(k[0], null, leaf);
    }

    /** The compiled permission rules. @throws IllegalArgumentException for an invalid regex */
    public PermissionRules permissionRules() {
        return permissions == null ? PermissionRules.none()
                : PermissionRules.parse(permissions.deny(), permissions.ask(), permissions.allow());
    }

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * @param maxParallel maximum model calls in flight, and chat runs / sub-agents running at once. Default {@code 1}:
     *                    one model call at a time, depth-first (made for a local LLM). {@code -1} = unlimited;
     *                    {@code 0} is treated as {@code 1}
     * @param useContextStack sub-agents continue the parent's request prefix (see
     *                    {@link HarnessConfig#withUseContextStack}); default {@code true}
     */
    public record Execution(Integer maxParallel, Boolean useContextStack) {
    }

    /**
     * @param tempDir            root of the per chat and agent scratch directories; default {@code java.io.tmpdir/agent-harness}
     * @param backend            {@code local} (default, no isolation) or {@code docker}
     * @param localFallback      with the docker backend: run locally when docker is unavailable (default true)
     * @param docker             docker backend settings; unset values keep the defaults
     */
    public record Sandbox(String tempDir, Integer timeoutSeconds, String backend, Boolean localFallback,
                          Docker docker) {

        public Sandbox(String tempDir, Integer timeoutSeconds) {
            this(tempDir, timeoutSeconds, null, null, null);
        }
    }

    /** Same names and meaning as {@link HarnessConfig.Docker}; unset values keep the defaults. */
    public record Docker(String binary, String image, String memory, String cpus, Integer pidsLimit, String user,
                         String network, String mode, Boolean remote, String remoteHost) {

        public Docker(String binary, String image, String memory, String cpus, Integer pidsLimit, String user,
                      String network) {
            this(binary, image, memory, cpus, pidsLimit, user, network, null, null, null);
        }

        public Docker(String binary, String image, String memory, String cpus, Integer pidsLimit, String user,
                      String network, String mode) {
            this(binary, image, memory, cpus, pidsLimit, user, network, mode, null, null);
        }

        HarnessConfig.Docker over(HarnessConfig.Docker d) {
            return new HarnessConfig.Docker(notBlank(binary, d.binary()), notBlank(image, d.image()),
                    notBlank(memory, d.memory()), notBlank(cpus, d.cpus()), pidsLimit != null ? pidsLimit : d.pidsLimit(),
                    notBlank(user, d.user()), notBlank(network, d.network()), notBlank(mode, d.mode()),
                    remote != null ? remote : d.remote(), notBlank(remoteHost, d.remoteHost()));
        }

        private static String notBlank(String v, String d) {
            return v == null || v.isBlank() ? d : v;
        }
    }

    /** True when the settings select the docker backend. */
    public boolean dockerSandbox() {
        return sandbox != null && sandbox.backend() != null && sandbox.backend().strip().equalsIgnoreCase("docker");
    }

    /** Same settings with {@code sandbox.backend} replaced (e.g. by a command line flag). */
    public HarnessSettings withSandboxBackend(String backend) {
        if (!"local".equalsIgnoreCase(backend) && !"docker".equalsIgnoreCase(backend)) {
            throw new IllegalArgumentException("Unknown sandbox backend '" + backend + "' (local or docker)");
        }
        Sandbox s = sandbox == null ? new Sandbox(null, null) : sandbox;
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution,
                new Sandbox(s.tempDir(), s.timeoutSeconds(), backend.toLowerCase(), s.localFallback(), s.docker()),
                context, fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** Same settings with {@code sandbox.docker.image} replaced (e.g. by a command line flag). */
    public HarnessSettings withDockerImage(String image) {
        Sandbox s = sandbox == null ? new Sandbox(null, null) : sandbox;
        Docker d = s.docker() == null ? new Docker(null, null, null, null, null, null, null, null) : s.docker();
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution,
                new Sandbox(s.tempDir(), s.timeoutSeconds(), s.backend(), s.localFallback(),
                        new Docker(d.binary(), image, d.memory(), d.cpus(), d.pidsLimit(), d.user(), d.network(),
                                d.mode(), d.remote(), d.remoteHost())),
                context, fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** Same settings with {@code sandbox.docker.mode} replaced (e.g. by a command line flag). */
    public HarnessSettings withDockerMode(String mode) {
        if (!"oneshot".equalsIgnoreCase(mode) && !"daemon".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Unknown docker mode '" + mode + "' (oneshot or daemon)");
        }
        Sandbox s = sandbox == null ? new Sandbox(null, null) : sandbox;
        Docker d = s.docker() == null ? new Docker(null, null, null, null, null, null, null, null) : s.docker();
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution,
                new Sandbox(s.tempDir(), s.timeoutSeconds(), s.backend(), s.localFallback(),
                        new Docker(d.binary(), d.image(), d.memory(), d.cpus(), d.pidsLimit(), d.user(), d.network(),
                                mode.toLowerCase(), d.remote(), d.remoteHost())),
                context, fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** Same settings with {@code sandbox.docker.remote} and {@code remoteHost} replaced (command line flags). */
    public HarnessSettings withDockerRemote(Boolean remote, String remoteHost) {
        Sandbox s = sandbox == null ? new Sandbox(null, null) : sandbox;
        Docker d = s.docker() == null ? new Docker(null, null, null, null, null, null, null, null, null, null) : s.docker();
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution,
                new Sandbox(s.tempDir(), s.timeoutSeconds(), s.backend(), s.localFallback(),
                        new Docker(d.binary(), d.image(), d.memory(), d.cpus(), d.pidsLimit(), d.user(), d.network(),
                                d.mode(), remote != null ? remote : d.remote(),
                                remoteHost != null ? remoteHost : d.remoteHost())),
                context, fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** True when the docker backend runs in daemon mode (long-running container). */
    public boolean dockerDaemon() {
        return dockerSandbox() && sandbox.docker() != null && sandbox.docker().mode() != null
                && sandbox.docker().mode().strip().equalsIgnoreCase("daemon");
    }

    /**
     * @param toolMaxChars  tool output kept in the context (default 30000)
     * @param eventMaxChars tool output carried by the {@code tool_result} event (default 2000)
     * @param validation    tool-call argument check: {@code off}, {@code lenient} (default) or {@code strict}
     */
    public record Output(Integer toolMaxChars, Integer eventMaxChars, String sanitize, Boolean taintGating,
                         java.util.List<String> extraPatterns, String validation) {
        public Output(Integer toolMaxChars, Integer eventMaxChars, String sanitize, Boolean taintGating,
                      java.util.List<String> extraPatterns) {
            this(toolMaxChars, eventMaxChars, sanitize, taintGating, extraPatterns, null);
        }

        public Output(Integer toolMaxChars, Integer eventMaxChars) {
            this(toolMaxChars, eventMaxChars, null, null, null, null);
        }
    }

    /**
     * @param maxTokens       context window; the compaction ceiling is derived from it
     * @param keepRecentTurns whole recent turns kept verbatim when compacting
     * @param auto            compact automatically (default true)
     * @param keepTokens      newest tokens kept verbatim when compacting
     * @param buffer          tokens kept free below maxTokens; default max(15%, 16k)
     * @param strategy        {@code compaction} (default) or {@code mnemonic}
     * @param mnemonic        settings of the mnemonic strategy
     * @param noiseCommands   remove shell commands that add no information (mkdir, rm, cd, ...) from the context
     *                        after their turn, see {@code NoiseCommandPruner} (default true)
     * @param deferCompactionOnThinkingOverflow Anthropic: do not compact while the turn in progress holds thinking
     *                        blocks (default false: compact and switch thinking off for the rest of the turn). true keeps
     *                        thinking but the prompt may exceed the budget until the turn ends: can increase charges
     */
    public record Context(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens,
                          Integer buffer, String strategy, Mnemonic mnemonic, Scope scope, Boolean noiseCommands,
                          Boolean deferCompactionOnThinkingOverflow) {

        public Context(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens,
                       Integer buffer, String strategy, Mnemonic mnemonic, Scope scope, Boolean noiseCommands) {
            this(maxTokens, keepRecentTurns, auto, keepTokens, buffer, strategy, mnemonic, scope, noiseCommands, null);
        }

        public Context(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens,
                       Integer buffer, String strategy, Mnemonic mnemonic, Scope scope) {
            this(maxTokens, keepRecentTurns, auto, keepTokens, buffer, strategy, mnemonic, scope, null, null);
        }

        public Context(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens, Integer buffer) {
            this(maxTokens, keepRecentTurns, auto, keepTokens, buffer, null, null, null);
        }

        public Context(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens, Integer buffer,
                       String strategy, Mnemonic mnemonic) {
            this(maxTokens, keepRecentTurns, auto, keepTokens, buffer, strategy, mnemonic, null);
        }
    }

    /** Thresholds of the {@code scope} context strategy; unset values keep the defaults. */
    public record Scope(Integer offloadChars, Integer overflowOffloadChars, Integer previewChars,
                        Integer safetyPercent, Integer reserveCap) {

        public ScopeStrategy.Options over(ScopeStrategy.Options d) {
            return new ScopeStrategy.Options(offloadChars != null ? offloadChars : d.offloadChars(),
                    overflowOffloadChars != null ? overflowOffloadChars : d.overflowOffloadChars(),
                    previewChars != null ? previewChars : d.previewChars(),
                    safetyPercent != null ? safetyPercent : d.safetyPercent(),
                    reserveCap != null ? reserveCap : d.reserveCap());
        }
    }

    /** The scope strategy options: {@code defaults} with the {@code context.scope} settings on top. */
    public ScopeStrategy.Options scopeOptions(ScopeStrategy.Options defaults) {
        return context == null || context.scope() == null ? defaults : context.scope().over(defaults);
    }

    /** Same names and meaning as {@link HarnessConfig.Mnemonic}; unset values keep the defaults. */
    public record Mnemonic(Integer maskTrigger, Integer maskTarget, Integer minBatchTokens, Integer minMaskTokens,
                           Integer keepRecentTokens, Integer minAgeTurns, Integer tailMaxTokens, Integer notesMaxTokens,
                           Integer indexMaxTokens, Integer recallMaxChars, Boolean dropOldThinking) {

        HarnessConfig.Mnemonic over(HarnessConfig.Mnemonic d) {
            return new HarnessConfig.Mnemonic(maskTrigger != null ? maskTrigger : d.maskTrigger(),
                    maskTarget != null ? maskTarget : d.maskTarget(),
                    minBatchTokens != null ? minBatchTokens : d.minBatchTokens(),
                    minMaskTokens != null ? minMaskTokens : d.minMaskTokens(),
                    keepRecentTokens != null ? keepRecentTokens : d.keepRecentTokens(),
                    minAgeTurns != null ? minAgeTurns : d.minAgeTurns(),
                    tailMaxTokens != null ? tailMaxTokens : d.tailMaxTokens(),
                    notesMaxTokens != null ? notesMaxTokens : d.notesMaxTokens(),
                    indexMaxTokens != null ? indexMaxTokens : d.indexMaxTokens(),
                    recallMaxChars != null ? recallMaxChars : d.recallMaxChars(),
                    dropOldThinking != null ? dropOldThinking : d.dropOldThinking());
        }
    }

    /** Settings that are set replace the matching value of {@code config}; the others are left as they are. */
    public HarnessConfig applyTo(HarnessConfig config) {
        HarnessConfig c = config;
        if (execution != null && execution.useContextStack() != null) c = c.withUseContextStack(execution.useContextStack());
        if (sandbox != null) {
            if (sandbox.tempDir() != null && !sandbox.tempDir().isBlank()) c = c.withSandboxBaseDir(sandbox.tempDir());
            if (sandbox.timeoutSeconds() != null) c = c.withSandboxTimeoutSeconds(sandbox.timeoutSeconds());
            if (sandbox.localFallback() != null) c = c.withLocalDirSandboxAllowed(sandbox.localFallback());
            if (sandbox.docker() != null) c = c.withDocker(sandbox.docker().over(c.sandbox().docker()));
        }
        if (context != null) {
            if (context.maxTokens() != null) c = c.withContextBudgetTokens(context.maxTokens());
            if (context.keepRecentTurns() != null) c = c.withKeepRecentTurns(context.keepRecentTurns());
            HarnessConfig.Compaction k = c.compaction();
            c = c.withCompaction(new HarnessConfig.Compaction(context.auto() != null ? context.auto() : k.auto(),
                    context.keepTokens() != null ? context.keepTokens() : k.keepTokens(),
                    context.buffer() != null ? context.buffer() : k.buffer(),
                    context.deferCompactionOnThinkingOverflow() != null
                            ? context.deferCompactionOnThinkingOverflow() : k.deferOnThinkingOverflow()));
            if (context.strategy() != null && !context.strategy().isBlank()) {
                c = c.withContextStrategy(ContextStrategyKind.parse(context.strategy()));
            }
            if (context.mnemonic() != null) c = c.withMnemonic(context.mnemonic().over(c.mnemonic()));
        }
        if (output != null) {
            HarnessConfig.Output o = c.output();
            c = c.withOutput(new HarnessConfig.Output(
                    output.toolMaxChars() != null ? output.toolMaxChars() : o.toolMaxChars(),
                    output.eventMaxChars() != null ? output.eventMaxChars() : o.eventMaxChars(),
                    output.sanitize() != null ? output.sanitize() : o.sanitize(),
                    output.taintGating() != null ? output.taintGating() : o.taintGating(),
                    output.extraPatterns() != null ? output.extraPatterns() : o.extraPatterns(),
                    output.validation() != null ? output.validation() : o.validation()));
        }
        return c;
    }

    /** Same settings with {@code context.maxTokens} replaced (e.g. by a command line flag). */
    public HarnessSettings withMaxTokens(int maxTokens) {
        Context c = context == null ? new Context(null, null, null, null, null) : context;
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox,
                new Context(maxTokens, c.keepRecentTurns(), c.auto(), c.keepTokens(), c.buffer(), c.strategy(),
                        c.mnemonic(), c.scope(), c.noiseCommands(), c.deferCompactionOnThinkingOverflow()), fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** Same settings with {@code context.strategy} replaced (e.g. by a command line flag). */
    public HarnessSettings withContextStrategy(String strategy) {
        ContextStrategyKind.parse(strategy);
        Context c = context == null ? new Context(null, null, null, null, null) : context;
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox,
                new Context(c.maxTokens(), c.keepRecentTurns(), c.auto(), c.keepTokens(), c.buffer(), strategy,
                        c.mnemonic(), c.scope(), c.noiseCommands(), c.deferCompactionOnThinkingOverflow()), fastModel, output, smallModel, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** False when {@code context.noiseCommands} is set to false. */
    public boolean noiseCommands() {
        return context == null || !Boolean.FALSE.equals(context.noiseCommands());
    }

    /** Same settings with {@code smallModel} replaced (e.g. by a command line flag). */
    public HarnessSettings withSmallModel(boolean small) {
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context,
                fastModel, output, small, permissions, providers, defaultProvider, chatRetentionLimit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** Same settings with {@code chatRetentionLimit} replaced. */
    public HarnessSettings withChatRetentionLimit(Integer limit) {
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context,
                fastModel, output, smallModel, permissions, providers, defaultProvider, limit, plugins, storage, whitelistedEnvVars,
                toolsWhitelistedEnvVars);
    }

    /** Concurrency limit: {@code 1} serialized (default), {@code N} bounded, {@code -1} unlimited. */
    public int maxParallel() {
        if (execution == null || execution.maxParallel() == null) return 1;
        int n = execution.maxParallel();
        return n < 0 ? -1 : Math.max(1, n);
    }

    public boolean isSmallModel() {
        return Boolean.TRUE.equals(smallModel);
    }

    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles, List<AgentSettings> agents) {
        this(mcpServers, restApis, skills, instructionFiles, agents, null);
    }

    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles, List<AgentSettings> agents,
                           Execution execution, Sandbox sandbox, Context context, LlmSettings fastModel) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context, fastModel, null);
    }

    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles, List<AgentSettings> agents,
                           Execution execution, Sandbox sandbox, Context context, LlmSettings fastModel,
                           Output output) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context, fastModel, output,
                null);
    }

    public HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                           List<SkillSettings> skills, List<String> instructionFiles,
                           List<AgentSettings> agents, Execution execution) {
        this(mcpServers, restApis, skills, instructionFiles, agents, execution, null, null, null);
    }

    public HarnessSettings {
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        restApis = restApis == null ? List.of() : List.copyOf(restApis);
        skills = skills == null ? List.of() : List.copyOf(skills);
        instructionFiles = instructionFiles == null ? List.of() : List.copyOf(instructionFiles);
        agents = agents == null ? List.of() : List.copyOf(agents);
        providers = providers == null ? List.of() : List.copyOf(providers);
        whitelistedEnvVars = org.kendar.llm.harness.util.EnvWhitelist.orAll(whitelistedEnvVars);
        toolsWhitelistedEnvVars = org.kendar.llm.harness.util.EnvWhitelist.orAll(toolsWhitelistedEnvVars);
        if (permissions != null) {
            PermissionRules.parse(permissions.deny(), permissions.ask(), permissions.allow()); // fail at startup
        }
    }

    public static HarnessSettings empty() {
        return new HarnessSettings(null, null, null, null, null, null, null, null, null);
    }

    public static HarnessSettings load(Path file) {
        return load(file, System::getenv);
    }

    /** @throws IllegalStateException if a referenced environment variable is not set */
    public static HarnessSettings load(Path file, Function<String, String> env) {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            JsonNode tree = expand(mapper.readTree(file.toFile()), env);
            return mapper.treeToValue(tree, HarnessSettings.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings " + file, e);
        }
    }

    /** The global settings file {@code <jllm.home or user.home>/.jllm/settings.json}. */
    public static Path globalFile() {
        return Path.of(System.getProperty("jllm.home", System.getProperty("user.home")), ".jllm", "settings.json");
    }

    /** The project settings file {@code <projectDir>/.jllm/settings.json}. */
    public static Path projectFile(Path projectDir) {
        return projectDir.resolve(".jllm").resolve("settings.json");
    }

    /**
     * Global, then project, then explicit file: later wins, objects merged per key, other values (including arrays)
     * replaced. Missing files are skipped; {@code explicit} and {@code projectDir} may be null.
     */
    public static HarnessSettings loadLayered(Path explicit, Path projectDir) {
        return loadLayered(explicit, projectDir, System::getenv);
    }

    public static HarnessSettings loadLayered(Path explicit, Path projectDir, Function<String, String> env) {
        List<Path> files = new ArrayList<>();
        files.add(globalFile());
        if (projectDir != null) files.add(projectFile(projectDir));
        if (explicit != null) files.add(explicit);
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode merged = null;
        try {
            for (Path f : files) {
                if (!java.nio.file.Files.isRegularFile(f)) {
                    if (f.equals(explicit)) throw new UncheckedIOException("Cannot read settings " + f,
                            new java.io.FileNotFoundException(f.toString()));
                    continue;
                }
                JsonNode tree = mapper.readTree(f.toFile());
                merged = merged == null ? tree : merge(merged, tree);
            }
            if (merged == null) return empty();
            return mapper.treeToValue(expand(merged, env), HarnessSettings.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings", e);
        }
    }

    static JsonNode merge(JsonNode base, JsonNode over) {
        if (base instanceof ObjectNode b && over instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode existing = b.get(e.getKey());
                b.set(e.getKey(), existing == null ? e.getValue() : merge(existing, e.getValue()));
            }
            return b;
        }
        return over;
    }

    static JsonNode expand(JsonNode node, Function<String, String> env) {
        if (node.isTextual()) return com.fasterxml.jackson.databind.node.TextNode.valueOf(expand(node.asText(), env));
        if (node instanceof ObjectNode obj) {
            for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                e.setValue(expand(e.getValue(), env));
            }
        } else if (node instanceof ArrayNode arr) {
            List<JsonNode> items = new ArrayList<>();
            arr.forEach(i -> items.add(expand(i, env)));
            arr.removeAll();
            arr.addAll(items);
        }
        return node;
    }

    static String expand(String text, Function<String, String> env) {
        Matcher m = ENV.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = env.apply(m.group(1));
            if (v == null) throw new IllegalStateException("Environment variable " + m.group(1) + " is not set");
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}

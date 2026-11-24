package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.kendar.llm.harness.context.scope.ScopeStrategy;
import org.kendar.llm.harness.instructions.ScopedInstructions;
import org.kendar.llm.harness.llm.LlmSettings;
import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.permission.PermissionRules;
import org.kendar.llm.harness.sandbox.LocalShell;
import org.kendar.llm.harness.tools.ExternalLinks;
import org.kendar.llm.harness.util.EnvWhitelist;

import java.util.List;

/**
 * The settings file of a harness. String values may use {@code ${ENV_VAR}}. Build with {@link #builder()} or
 * {@link #toBuilder()}, read files with {@link HarnessSettingsLoader}, apply to a config with
 * {@link HarnessConfigMapper}.
 *
 * @param instructionFiles files added to the system prompt, in order; missing files are skipped. Null or absent =
 *                         {@link #DEFAULT_INSTRUCTION_FILES}; a list replaces the default
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
 * @param whitelistedEnvVars environment variables of the harness process that shell commands (bash) may inherit;
 *                         patterns with * (any run of characters, case-insensitive: "*key*", "PATH"); default
 *                         ["*"] = all, [] = none
 * @param toolsWhitelistedEnvVars the same for the processes of tools (MCP stdio servers); the {@code env} of a server
 *                         in the settings is always added on top
 * @param loop             optional stuck detection and verify-on-stop settings
 * @param hooks            optional command hooks ({@code stop}); from the project file only when trusted
 * @param tokenStore       where OAuth tokens are kept; from the user settings and {@code --settings} only (a project
 *                         file's section is ignored)
 */
public record HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                              List<SkillSettings> skills, List<String> instructionFiles,
                              List<AgentSettings> agents, ExecutionSettings execution, SandboxSettings sandbox,
                              ContextSettings context, LlmSettings fastModel, OutputSettings output,
                              Boolean smallModel, PermissionSettings permissions,
                              List<ProviderSettings> providers, String defaultProvider,
                              Integer chatRetentionLimit, JsonNode plugins, StorageSettings storage,
                              List<String> whitelistedEnvVars, List<String> toolsWhitelistedEnvVars,
                              FileLimits files, WebLimits web, LoopSettings loop, HookSettings hooks,
                              TokenStoreSettings tokenStore) {

    /** Default of {@code files.maxReadBytes}. */
    public static final long DEFAULT_FILES_MAX_READ_BYTES = 1_000_000;
    /** Default of {@code web.fetchMaxBytes}: 5 MB. */
    public static final long DEFAULT_WEB_FETCH_MAX_BYTES = 5L * 1024 * 1024;
    /** Default of {@code sandbox.outputLogMaxBytes}: 50 KB. */
    public static final long DEFAULT_SANDBOX_OUTPUT_LOG_MAX_BYTES = 51_200;
    /** Default of {@code instructionFiles}, in this order. */
    public static final List<String> DEFAULT_INSTRUCTION_FILES = List.of("KENDAR.md", "AGENTS.md", "CLAUDE.md");

    public HarnessSettings {
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        restApis = restApis == null ? List.of() : List.copyOf(restApis);
        skills = skills == null ? List.of() : List.copyOf(skills);
        instructionFiles = instructionFiles == null ? DEFAULT_INSTRUCTION_FILES : List.copyOf(instructionFiles);
        agents = agents == null ? List.of() : List.copyOf(agents);
        providers = providers == null ? List.of() : List.copyOf(providers);
        whitelistedEnvVars = EnvWhitelist.orAll(whitelistedEnvVars);
        toolsWhitelistedEnvVars = EnvWhitelist.orAll(toolsWhitelistedEnvVars);
        if (permissions != null) {
            PermissionRules.parse(permissions.deny(), permissions.ask(), permissions.allow()); // fail at startup
        }
    }

    public static HarnessSettingsBuilder builder() {
        return new HarnessSettingsBuilder();
    }

    /** A builder that starts from these settings. */
    public HarnessSettingsBuilder toBuilder() {
        return new HarnessSettingsBuilder(this);
    }

    public static HarnessSettings empty() {
        return builder().build();
    }

    /**
     * Same settings with {@code plugins.<id>.enabled} replaced (e.g. by {@code --plugin}); an id with no entry gets
     * one. The other keys of the entry are kept. A null {@code enabled} or {@code pluginSettings} keeps the current
     * value; given settings are merged over the current ones.
     */
    public HarnessSettings withPlugin(String id, Boolean enabled, JsonNode pluginSettings) {
        ObjectNode copy = plugins != null && plugins.isObject() ? ((ObjectNode) plugins).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        JsonNode entry = copy.get(id);
        ObjectNode e = entry instanceof ObjectNode o ? o : copy.putObject(id);
        if (enabled != null) e.put("enabled", enabled.booleanValue());
        if (pluginSettings != null) {
            e.set("settings", HarnessSettingsLoader.merge(e.has("settings") ? e.get("settings") : pluginSettings,
                    pluginSettings));
        }
        return toBuilder().plugins(copy).build();
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
        var f = JsonNodeFactory.instance;
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

    /** The stop command hooks, possibly none. */
    public List<StopHook> stopHooks() {
        return hooks == null ? List.of() : hooks.stop();
    }

    /** False when {@code files.scopedInstructions} is set to false. */
    public boolean scopedInstructions() {
        return files == null || files.scopedInstructions() == null || files.scopedInstructions();
    }

    /** {@code files.scopedInstructionsDepth}, or its default. */
    public int scopedInstructionsDepth() {
        Integer v = files == null ? null : files.scopedInstructionsDepth();
        return v == null || v < 0 ? ScopedInstructions.DEFAULT_MAX_DEPTH : v;
    }

    /** {@code files.externalLinks}, or its default ({@link ExternalLinks#DEFAULT}). */
    public ExternalLinks externalLinks() {
        return files == null || files.externalLinks() == null ? ExternalLinks.DEFAULT : files.externalLinks();
    }

    /** {@code files.maxReadBytes}, or its default. */
    public long filesMaxReadBytes() {
        return files == null || files.maxReadBytes() == null || files.maxReadBytes() <= 0
                ? DEFAULT_FILES_MAX_READ_BYTES : files.maxReadBytes();
    }

    /** {@code web.fetchMaxBytes}, or its default. */
    public long webFetchMaxBytes() {
        return web == null || web.fetchMaxBytes() == null || web.fetchMaxBytes() <= 0
                ? DEFAULT_WEB_FETCH_MAX_BYTES : web.fetchMaxBytes();
    }

    /** {@code web.blockLocalHost}, default false. */
    public boolean webBlockLocalHost() {
        return web != null && Boolean.TRUE.equals(web.blockLocalHost());
    }

    /** {@code web.blockExtraAddresses}, default none. */
    public List<String> webBlockExtraAddresses() {
        return web == null ? List.of() : web.blockExtraAddresses();
    }

    /** {@code sandbox.shell}, or the OS default shell. */
    public LocalShell sandboxShell() {
        return LocalShell.of(sandbox == null ? null : sandbox.shell());
    }

    /** {@code sandbox.outputLogMaxBytes}, or its default; 0 = unlimited (a negative value is the default too). */
    public long sandboxOutputLogMaxBytes() {
        Long v = sandbox == null ? null : sandbox.outputLogMaxBytes();
        return v == null || v < 0 ? DEFAULT_SANDBOX_OUTPUT_LOG_MAX_BYTES : v;
    }

    /** The storage cap in bytes; -1 = no limit. */
    public long storageMaxBytes() {
        return storage == null || storage.maxBytes() == null ? -1 : storage.maxBytes();
    }

    /** The compiled permission rules. @throws IllegalArgumentException for an invalid regex */
    public PermissionRules permissionRules() {
        return permissions == null ? PermissionRules.none()
                : PermissionRules.parse(permissions.deny(), permissions.ask(), permissions.allow());
    }

    /** True when the settings select the docker backend: the container runner ({@code RemoteExecSandboxExecutor}). */
    public boolean dockerSandbox() {
        return sandbox != null && sandbox.backend() != null && sandbox.backend().strip().equalsIgnoreCase("docker");
    }

    /** The scope strategy options: {@code defaults} with the {@code context.scope} settings on top. */
    public ScopeStrategy.Options scopeOptions(ScopeStrategy.Options defaults) {
        return context == null || context.scope() == null ? defaults : context.scope().over(defaults);
    }

    /** False only when {@code context.todos} is set to false: by default the todo tools are offered with any strategy. */
    public boolean todos() {
        return context == null || !Boolean.FALSE.equals(context.todos());
    }

    /** False when {@code context.noiseCommands} is set to false. */
    public boolean noiseCommands() {
        return context == null || !Boolean.FALSE.equals(context.noiseCommands());
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
}

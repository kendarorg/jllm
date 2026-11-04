package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.ContextStrategyKind;
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
 */
public record HarnessSettings(List<McpServerSettings> mcpServers, List<RestApiSettings> restApis,
                              List<SkillSettings> skills, List<String> instructionFiles,
                              List<AgentSettings> agents, Execution execution, Sandbox sandbox, Context context,
                              LlmSettings fastModel, Output output, Boolean smallModel, Permissions permissions,
                              List<ProviderSettings> providers, String defaultProvider) {

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
                smallModel, null, null, null);
    }

    /** The compiled permission rules. @throws IllegalArgumentException for an invalid regex */
    public PermissionRules permissionRules() {
        return permissions == null ? PermissionRules.none()
                : PermissionRules.parse(permissions.deny(), permissions.ask(), permissions.allow());
    }

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * @param serializeModelCalls one model call at a time, depth-first: main, agent, each sub-agent to its end, back to
     *                            the agent, back to main (default true: made for a local LLM)
     */
    public record Execution(Boolean serializeModelCalls) {
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
                context, fastModel, output, smallModel, permissions, providers, defaultProvider);
    }

    /** Same settings with {@code sandbox.docker.image} replaced (e.g. by a command line flag). */
    public HarnessSettings withDockerImage(String image) {
        Sandbox s = sandbox == null ? new Sandbox(null, null) : sandbox;
        Docker d = s.docker() == null ? new Docker(null, null, null, null, null, null, null, null) : s.docker();
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution,
                new Sandbox(s.tempDir(), s.timeoutSeconds(), s.backend(), s.localFallback(),
                        new Docker(d.binary(), image, d.memory(), d.cpus(), d.pidsLimit(), d.user(), d.network(),
                                d.mode(), d.remote(), d.remoteHost())),
                context, fastModel, output, smallModel, permissions, providers, defaultProvider);
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
                context, fastModel, output, smallModel, permissions, providers, defaultProvider);
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
                context, fastModel, output, smallModel, permissions, providers, defaultProvider);
    }

    /** True when the docker backend runs in daemon mode (long-running container). */
    public boolean dockerDaemon() {
        return dockerSandbox() && sandbox.docker() != null && sandbox.docker().mode() != null
                && sandbox.docker().mode().strip().equalsIgnoreCase("daemon");
    }

    /**
     * @param toolMaxChars  tool output kept in the context (default 30000)
     * @param eventMaxChars tool output carried by the {@code tool_result} event (default 2000)
     */
    public record Output(Integer toolMaxChars, Integer eventMaxChars) {
    }

    /**
     * @param maxTokens       context window; the compaction ceiling is derived from it
     * @param keepRecentTurns whole recent turns kept verbatim when compacting
     * @param auto            compact automatically (default true)
     * @param keepTokens      newest tokens kept verbatim when compacting
     * @param buffer          tokens kept free below maxTokens; default max(15%, 16k)
     * @param strategy        {@code compaction} (default) or {@code mnemonic}
     * @param mnemonic        settings of the mnemonic strategy
     */
    public record Context(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens,
                          Integer buffer, String strategy, Mnemonic mnemonic) {

        public Context(Integer maxTokens, Integer keepRecentTurns, Boolean auto, Integer keepTokens, Integer buffer) {
            this(maxTokens, keepRecentTurns, auto, keepTokens, buffer, null, null);
        }
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
                    context.buffer() != null ? context.buffer() : k.buffer()));
            if (context.strategy() != null && !context.strategy().isBlank()) {
                c = c.withContextStrategy(ContextStrategyKind.parse(context.strategy()));
            }
            if (context.mnemonic() != null) c = c.withMnemonic(context.mnemonic().over(c.mnemonic()));
        }
        if (output != null) {
            HarnessConfig.Output o = c.output();
            c = c.withOutput(new HarnessConfig.Output(
                    output.toolMaxChars() != null ? output.toolMaxChars() : o.toolMaxChars(),
                    output.eventMaxChars() != null ? output.eventMaxChars() : o.eventMaxChars()));
        }
        return c;
    }

    /** Same settings with {@code context.maxTokens} replaced (e.g. by a command line flag). */
    public HarnessSettings withMaxTokens(int maxTokens) {
        Context c = context == null ? new Context(null, null, null, null, null) : context;
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox,
                new Context(maxTokens, c.keepRecentTurns(), c.auto(), c.keepTokens(), c.buffer(), c.strategy(),
                        c.mnemonic()), fastModel, output, smallModel, permissions, providers, defaultProvider);
    }

    /** Same settings with {@code context.strategy} replaced (e.g. by a command line flag). */
    public HarnessSettings withContextStrategy(String strategy) {
        ContextStrategyKind.parse(strategy);
        Context c = context == null ? new Context(null, null, null, null, null) : context;
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox,
                new Context(c.maxTokens(), c.keepRecentTurns(), c.auto(), c.keepTokens(), c.buffer(), strategy,
                        c.mnemonic()), fastModel, output, smallModel, permissions, providers, defaultProvider);
    }

    /** Same settings with {@code smallModel} replaced (e.g. by a command line flag). */
    public HarnessSettings withSmallModel(boolean small) {
        return new HarnessSettings(mcpServers, restApis, skills, instructionFiles, agents, execution, sandbox, context,
                fastModel, output, small, permissions, providers, defaultProvider);
    }

    public boolean serializeModelCalls() {
        return execution == null || execution.serializeModelCalls() == null || execution.serializeModelCalls();
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

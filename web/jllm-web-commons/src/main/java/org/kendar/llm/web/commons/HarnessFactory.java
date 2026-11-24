package org.kendar.llm.web.commons;

import org.kendar.llm.harness.identity.HarnessIdentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.context.mnemonic.FileMnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelClientFactory;
import org.kendar.llm.harness.sandbox.ChatDirLayout;
import org.kendar.llm.harness.sandbox.DisabledSandboxExecutor;
import org.kendar.llm.harness.sandbox.RoutedSandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxKind;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.FileChatSessionStore;
import org.kendar.llm.harness.session.HistoryRootLock;
import org.kendar.llm.harness.session.FileHistorySubAgentRunLog;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.settings.FileProjectMcpTrust;
import org.kendar.llm.harness.settings.FileSettingsSource;
import org.kendar.llm.harness.settings.HarnessConfigMapper;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.settings.ProjectMcpTrust;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.state.FileStateStore;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.harness.tools.ExternalLinks;

import java.nio.file.Path;

/**
 * Builds the {@link AgentHarness} and what the web host needs around it, from the {@code jllm.*} settings. A part the
 * application keeps elsewhere (a database) is passed in; null means the file-based default.
 */
public final class HarnessFactory {

    private HarnessFactory() {
    }


    public static ModelClient modelClient(JllmProperties p, ObjectMapper mapper) {
        return ModelClientFactory.create(p.providerOrDefault(), p.apiKey(), p.baseUrl(), p.model(),
                p.maxTokensOrDefault(), mapper);
    }

    public static HarnessPricing pricing(JllmProperties p) {
        var pr = p.pricing();
        if (pr == null) return new HarnessPricing(0, 0, 0, 0);
        return new HarnessPricing(nz(pr.inputPerMillion()), nz(pr.outputPerMillion()),
                nz(pr.cacheReadPerMillion()), nz(pr.cacheWritePerMillion()));
    }

    /**
     * @param settingsSource where the settings layers are read (null: global, project and the settings file)
     * @param projectTrust   where the trust answers are remembered (null: a file)
     * @param sessionStore   the chats (null: files under each chat's history root)
     * @param pluginStorages the plugins' storage (null: files)
     */
    public static AgentHarness harness(JllmProperties p, ModelClient modelClient, HarnessPricing pricing,
                                       ObjectMapper mapper, HarnessIdentity identity,
                                       SettingsSource settingsSource,
                                       ProjectMcpTrust projectTrust,
                                       ChatSessionStore sessionStore,
                                       SubAgentRunLog subAgentRunLog,
                                       MnemonicStore mnemonicStore,
                                       StateStore stateStore,
                                       org.kendar.llm.harness.plugin.ServiceRegistry services,
                                       org.kendar.llm.harness.plugin.PluginStorages pluginStorages) {
        String settingsFile = JllmProperties.blankToNull(p.settings());
        Path projectDir = Path.of(p.workdir() == null ? "" : p.workdir()).toAbsolutePath().normalize();
        // default: global ~/.jllm/settings.json, then project .jllm/settings.json, then the settings file; later wins.
        // An application keeping the layers elsewhere (a database) supplies a SettingsSource bean, and where the trust
        // answers are remembered with a ProjectMcpTrust bean.
        SettingsSource source = settingsSource != null ? settingsSource
                : new FileSettingsSource(settingsFile == null ? null : Path.of(settingsFile), projectDir);
        // suspicious content in the loaded files: logged, and listed to the users by /api/content-warnings
        var contentWarnings = new org.kendar.llm.harness.safety.ContentWarnings();
        HarnessSettings settings = HarnessSettingsLoader.loadLayered(source, projectDir, System::getenv,
                ProjectMcpTrust.Prompt.NONE, projectTrust != null ? projectTrust : FileProjectMcpTrust.user(),
                org.slf4j.LoggerFactory.getLogger(HarnessFactory.class)::warn, contentWarnings);
        var promptFiles = org.kendar.llm.harness.instructions.SystemPromptFiles.load(projectDir);
        String promptReplace = JllmProperties.blankToNull(p.systemPromptReplace()) != null
                ? org.kendar.llm.harness.instructions.SystemPromptFiles.readFile(Path.of(p.systemPromptReplace()))
                : promptFiles.replace();
        String promptAppend = org.kendar.llm.harness.instructions.SystemPromptFiles.join(
                org.kendar.llm.harness.instructions.SystemPromptFiles.join(promptFiles.append(),
                        JllmProperties.blankToNull(p.systemPromptFile()) != null
                                ? org.kendar.llm.harness.instructions.SystemPromptFiles.readFile(Path.of(p.systemPromptFile()))
                                : null),
                JllmProperties.blankToNull(p.systemPrompt()));
        // same precedence as the CLI: the property wins over the settings file
        var b = withoutExternalLinks(settings).toBuilder();
        if (p.contextTokens() != null && p.contextTokens() > 0) b.maxTokens(p.contextTokens());
        if (JllmProperties.blankToNull(p.contextStrategy()) != null) b.contextStrategy(p.contextStrategy());
        if (Boolean.TRUE.equals(p.smallModel())) b.smallModel(true);
        if (JllmProperties.blankToNull(p.sandbox()) != null) {
            LOG.warn("jllm.sandbox={} is ignored: on the web the sandbox (none, local, docker-isolated or docker-open) is set per role by an "
                    + "administrator; jllm.docker-* only set the defaults of the docker sandboxes", p.sandbox());
        }
        if (JllmProperties.blankToNull(p.dockerImage()) != null) b.dockerImage(p.dockerImage());
        if (JllmProperties.blankToNull(p.dockerScope()) != null) b.dockerScope(p.dockerScope());
        if (JllmProperties.blankToNull(p.dockerProfile()) != null) b.dockerProfile(p.dockerProfile());
        if (p.dockerRemote() != null || JllmProperties.blankToNull(p.dockerRemoteHost()) != null) {
            b.dockerRemote(p.dockerRemote(), JllmProperties.blankToNull(p.dockerRemoteHost()));
        }
        settings = b.build();
        if (p.plugins() != null) {
            for (var e : p.plugins().entrySet()) {
                if (e.getValue() == null) continue;
                settings = settings.withPlugin(e.getKey(), e.getValue().enabled(),
                        e.getValue().settings() == null ? null : mapper.valueToTree(e.getValue().settings()));
            }
        }
        settings = withUntrustedScriptPolicy(settings);
        services.publish(org.kendar.llm.harness.plugin.PluginsSettings.class,
                new org.kendar.llm.harness.plugin.PluginsSettings(settings.plugins()));
        HarnessConfig config = HarnessConfigMapper.apply(settings, HarnessConfig.defaults());
        String searchKey = JllmProperties.blankToNull(p.searchApiKey());
        if (searchKey == null) searchKey = System.getenv("BRAVE_API_KEY");
        if (searchKey != null) config = config.withSearchApiKey(searchKey);
        if (p.maxTurns() != null && p.maxTurns() > 0) config = config.withMaxTurns(p.maxTurns());
        config = config.withMaxTokens(p.maxTokensOrDefault());

        ChatDirLayout sandbox = new ChatDirLayout(projectDir);
        HistoryRootLock.acquire(sandbox.location()); // one process per base directory; held until the process ends
        // plugins find the host's plugin storage in the shared registry (files unless the application supplies another)
        services.publish(org.kendar.llm.harness.plugin.PluginStorages.class, pluginStorages != null ? pluginStorages
                : new org.kendar.llm.harness.plugin.FilePluginStorage(
                projectDir.resolve(".jllm").resolve("plugin-data"), mapper));
        AgentHarnessBuilder builder = new AgentHarnessBuilder().services(services);
        if (JllmProperties.blankToNull(p.pluginDir()) != null) builder.pluginDir(Path.of(p.pluginDir()));
        else if (java.nio.file.Files.isDirectory(Path.of("plugins"))) builder.pluginDir(Path.of("plugins"));
        // the sandbox is built before the harness: it asks the chat store, later, whose chat a container belongs to
        var harnessRef = new java.util.concurrent.atomic.AtomicReference<AgentHarness>();
        AgentHarness harness = builder
                .modelClient(modelClient)
                .config(config)
                .systemPrompt(promptAppend)
                .systemPromptReplace(promptReplace)
                .sandbox(executor(identity, config, sandbox, harnessRef))
                // chat history: files under each chat's history root, unless the application supplies the beans
                .sessionStore(sessionStore != null ? sessionStore
                        : new FileChatSessionStore(sandbox::historyRoot, () -> chatHistoryRoots(sandbox), mapper))
                .subAgentRunLog(subAgentRunLog != null ? subAgentRunLog
                        : new FileHistorySubAgentRunLog(new InMemorySubAgentRunLog(), sandbox::historyRoot))
                .mnemonicStore(mnemonicStore != null ? mnemonicStore : new FileMnemonicStore(sandbox::historyRoot))
                .stateStore(stateStore != null ? stateStore : new FileStateStore(sandbox::historyRoot))
                .settings(settings)
                .pricing(pricing)
                .objectMapper(mapper)
                .credentialResolver(identity.credentials())
                .userPolicyResolver(identity.policies())
                .principalDirectory(identity.principals())
                .tokenStoreDir(identity.tokenDir())
                .tokenStore(identity.tokenStore())
                .contentWarnings(contentWarnings)
                .discoverPlugins()
                .build();
        harnessRef.set(harness);
        harness.enforceChatRetention(settings.chatRetentionLimit(), null);
        return harness;
    }

    /** {@code <base>/<chatId>/.jllm/history} of every chat directory below the base; dot-directories (sandbox data) are not chats. */
    private static java.util.List<Path> chatHistoryRoots(ChatDirLayout sandbox) {
        Path base = sandbox.location();
        if (!java.nio.file.Files.isDirectory(base)) return java.util.List.of();
        try (var s = java.nio.file.Files.list(base)) {
            return s.filter(java.nio.file.Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .map(d -> sandbox.historyRoot(d.getFileName().toString()))
                    .filter(java.nio.file.Files::isDirectory).toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The runner of the chats: one per sandbox kind, chosen by the role of the chat's user (see {@link
     * RoutedSandboxExecutor}). Roles have no sandbox unless an administrator gives them one: file tools and the shell
     * then fail with an error the model sees. A chat's user is the principal the host attached to it. Building the
     * container runner does not touch docker; it is used only by the chats whose role says docker.
     */
    private static org.kendar.llm.harness.sandbox.SandboxExecutor executor(HarnessIdentity identity, HarnessConfig config,
                                                                          ChatDirLayout layout,
                                                                          java.util.concurrent.atomic.AtomicReference<AgentHarness> harness) {
        java.util.function.Function<String, String> userOf = chatId -> {
            AgentHarness h = harness.get();
            return h == null ? null : h.sessions().find(chatId)
                    .map(org.kendar.llm.harness.session.ChatSessionData::principal).orElse(null);
        };
        java.util.function.Function<String, org.kendar.llm.harness.identity.RoleSandbox> roleSandboxOf = chatId -> {
            String user = userOf.apply(chatId);
            return user == null ? org.kendar.llm.harness.identity.RoleSandbox.NONE : identity.sandboxes().apply(user);
        };
        var docker = new org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor(config,
                new org.kendar.llm.harness.sandbox.ProcessDockerCommandRunner(), System.getenv("DOCKER_HOST"), layout,
                org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.Kind.WEB, layout.location(), userOf,
                chatId -> {
                    var rs = roleSandboxOf.apply(chatId);
                    return new org.kendar.llm.harness.sandbox.SandboxPolicy(rs.profile(), rs.scope(), null, null, null,
                            rs.network(), null, null);
                }, null);
        docker.onWarning(LOG::warn);
        return new RoutedSandboxExecutor(roleSandboxOf, java.util.Map.of(
                SandboxKind.NONE, new DisabledSandboxExecutor(layout),
                SandboxKind.LOCAL, new org.kendar.llm.harness.sandbox.LocalSandboxExecutor(layout),
                SandboxKind.DOCKER_ISOLATED, docker, SandboxKind.DOCKER_OPEN, docker), layout);
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HarnessFactory.class);

    /**
     * The settings with {@code files.externalLinks} forced to {@code none}: the web host never follows a link out of the
     * project, whatever the settings say. One WARN when the key names another value, which is ignored.
     */
    public static HarnessSettings withoutExternalLinks(HarnessSettings settings) {
        var f = settings.files();
        if (f != null && f.externalLinks() != null && f.externalLinks() != ExternalLinks.NONE) {
            LOG.warn("files.externalLinks={} is ignored: the web host never follows a link that leaves the project "
                    + "directory (it always uses none)", f.externalLinks().settingValue());
        }
        return settings.toBuilder().externalLinks(ExternalLinks.NONE).build();
    }

    /**
     * The web host serves untrusted users: unless the {@code js} plugin entry names a {@code policy}, its isolated
     * profile uses the {@code untrusted} sandbox policy. Does nothing when the plugin is not listed and enabled.
     */
    public static HarnessSettings withUntrustedScriptPolicy(HarnessSettings settings) {
        var plugins = settings.plugins();
        var js = plugins == null ? null : plugins.get("js");
        if (js == null || !js.path("enabled").asBoolean(false) || js.path("settings").has("policy")) return settings;
        var policy = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("policy", "untrusted");
        return settings.withPlugin("js", null, policy);
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    /**
     * Loads the web plugins listed and enabled in the harness settings' {@code plugins} section (properties win). Built
     * after the harness, whose plugins they may need.
     */
    public static org.kendar.llm.harness.plugin.WebPluginHost webPluginHost(AgentHarness harness, JllmProperties p) {
        var storages = harness.services().lookup(org.kendar.llm.harness.plugin.PluginStorages.class).orElseThrow();
        var listed = harness.services().lookup(org.kendar.llm.harness.plugin.PluginsSettings.class)
                .map(org.kendar.llm.harness.plugin.PluginsSettings::plugins).orElse(null);
        var host = new org.kendar.llm.harness.plugin.WebPluginHost(harness.services(), storages, listed);
        String dir = JllmProperties.blankToNull(p.pluginDir());
        Path pluginDir = dir != null ? Path.of(dir)
                : java.nio.file.Files.isDirectory(Path.of("plugins")) ? Path.of("plugins") : null;
        host.load(org.kendar.llm.harness.plugin.WebPluginHost.discover(pluginDir));
        host.warnings().forEach(w -> org.slf4j.LoggerFactory.getLogger(
                org.kendar.llm.harness.plugin.WebPluginHost.class).warn(w));
        return host;
    }

    /**
     * At startup, with the container runner, removes the web sandbox containers whose chat no longer exists (left behind by
     * a crash or a chat deleted while the application was down). CLI containers are never touched.
     */
    public static void sweepOrphanContainers(AgentHarness harness) {
        if (!(harness.sandbox() instanceof RoutedSandboxExecutor routed)
                || !(routed.docker() instanceof org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor remote)) {
            return;
        }
        try {
            var removed = remote.sweepOrphans(chatId -> harness.sessions().find(chatId).isPresent());
            if (!removed.isEmpty()) LOG.info("Removed orphan sandbox containers: {}", removed);
        } catch (RuntimeException e) { // boundary: no docker on this host must not stop the server
            LOG.info("Orphan container sweep skipped: {}", e.toString());
        }
    }
}

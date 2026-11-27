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
import org.kendar.llm.harness.settings.ConfigProvider;
import org.kendar.llm.harness.settings.PathSettings;
import org.kendar.llm.harness.settings.VirtualConfigProvider;
import org.kendar.llm.harness.settings.HarnessConfigMapper;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.settings.FolderTrust;
import org.kendar.llm.harness.settings.SettingsSink;
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
     * @param provider       where the configuration comes from: a {@link VirtualConfigProvider} (database or
     *                       server-only directory); anything else, or none, stops the start. No file of a working or chat
     *                       directory is ever read
     * @param settingsSink   the settings an administrator stored at runtime: their sections join the layers last, so a
     *                       stored value wins (null: none). Read when the harness is built
     * @param sessionStore   the chats (null: files under each chat's history root)
     */
    public static AgentHarness harness(JllmProperties p, ModelClient modelClient, HarnessPricing pricing,
                                       ObjectMapper mapper, HarnessIdentity identity,
                                       ConfigProvider provider,
                                       SettingsSink settingsSink,
                                       ChatSessionStore sessionStore,
                                       SubAgentRunLog subAgentRunLog,
                                       MnemonicStore mnemonicStore,
                                       StateStore stateStore,
                                       org.kendar.llm.harness.plugin.ServiceRegistry services) {
        if (!(provider instanceof VirtualConfigProvider virtual)) {
            throw new IllegalStateException("The web host needs a virtual configuration: a SqlConfigProvider (jllm.store=sql) "
                    + "or a RemoteFileConfigProvider (jllm.store=file), not "
                    + (provider == null ? "none" : provider.getClass().getSimpleName()));
        }
        Path chatsDir = p.chatsPath();
        java.util.Map<String, Path> serverPaths = new java.util.LinkedHashMap<>();
        for (Path sp : virtual.serverPaths()) serverPaths.put("the configuration (" + virtual.describe() + ")", sp);
        String pluginDirName = JllmProperties.blankToNull(p.pluginDir());
        if (pluginDirName != null) serverPaths.put("jllm.plugin-dir", Path.of(pluginDirName));
        ChatsDirGuard.check(chatsDir, serverPaths);
        SettingsSource source = virtualOnly(virtual.settings());
        if (settingsSink != null) source = settingsSink.over(source);
        SettingsSource layers = source;
        SettingsSource checked = () -> {
            var all = layers.layers();
            for (var layer : all) PathSettings.check(layer.tree(), layer.origin());
            return all;
        };
        // suspicious content in the settings: logged, and listed to the users by /api/content-warnings
        var contentWarnings = new org.kendar.llm.harness.safety.ContentWarnings();
        HarnessSettings settings = HarnessSettingsLoader.loadLayered(checked, null, System::getenv,
                FolderTrust.Prompt.NONE, null,
                org.slf4j.LoggerFactory.getLogger(HarnessFactory.class)::warn, contentWarnings);
        var instructions = virtual.instructions();
        String promptReplace = instructions.systemPromptReplace().orElse(null);
        String promptAppend = org.kendar.llm.harness.instructions.SystemPromptFiles.join(
                org.kendar.llm.harness.instructions.SystemPromptFiles.join(instructions.systemPromptAppend(),
                        instructions.renderGlobal()),
                JllmProperties.blankToNull(p.systemPrompt()));
        // same precedence as the CLI: the property wins over the settings file
        var b = withoutStdioMcpServers(withoutExternalLinks(settings)).toBuilder();
        b.instructionFiles(java.util.List.of()); // no instruction file is read from a disk of the server
        b.agents(mergeAgents(settings.agents(), virtual.agents().all()));
        if (p.contextTokens() != null && p.contextTokens() > 0) b.maxTokens(p.contextTokens());
        if (JllmProperties.blankToNull(p.contextStrategy()) != null) b.contextStrategy(p.contextStrategy());
        if (Boolean.TRUE.equals(p.smallModel())) b.smallModel(true);
        if (JllmProperties.blankToNull(p.sandbox()) != null) {
            LOG.warn("jllm.sandbox={} is ignored: on the web the sandbox (none, shared-local, docker-isolated or docker-open) is set per role by an "
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

        ChatDirLayout sandbox = new ChatDirLayout(chatsDir);
        HistoryRootLock.acquire(sandbox.location()); // one process per base directory; held until the process ends
        // plugins find the host's plugin storage in the shared registry: the provider's
        services.publish(org.kendar.llm.harness.plugin.PluginStorages.class, virtual.pluginStorages());
        // flows may be marked unattended only by administrators and by roles that have the flag
        services.publish(org.kendar.llm.harness.workflows.UnattendedRoles.class, identity::mayRunUnattended);
        AgentHarnessBuilder builder = new AgentHarnessBuilder().services(services)
                .scopedInstructionFiles(HarnessSettings.DEFAULT_INSTRUCTION_FILES);
        if (JllmProperties.blankToNull(p.pluginDir()) != null) builder.pluginDir(Path.of(p.pluginDir()));
        else if (java.nio.file.Files.isDirectory(Path.of("plugins"))) builder.pluginDir(Path.of("plugins"));
        // the sandbox is built before the harness: it asks the chat store, later, whose chat a container belongs to
        var harnessRef = new java.util.concurrent.atomic.AtomicReference<AgentHarness>();
        AgentHarness harness = builder
                .modelClient(modelClient)
                .config(config)
                .systemPrompt(promptAppend)
                .systemPromptReplace(promptReplace)
                .sandbox(executor(identity, config, sandbox,
                        sharedLocalRequired(p, settings.sharedLocalSandbox(), identity.usesSharedLocal()), harnessRef))
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

    /** The settings layers of a virtual provider: a project layer cannot exist (there is no project folder). */
    private static SettingsSource virtualOnly(SettingsSource base) {
        return () -> {
            var all = base.layers();
            for (var l : all) {
                if (l.kind() == org.kendar.llm.harness.settings.SettingsLayer.Kind.PROJECT) {
                    throw new IllegalStateException("Settings " + l.origin() + " is a project layer: the web host has no project folder");
                }
            }
            return all;
        };
    }

    /** The agents of the settings, the provider's agents replacing those of the same name. */
    static java.util.List<org.kendar.llm.harness.settings.AgentSettings> mergeAgents(
            java.util.List<org.kendar.llm.harness.settings.AgentSettings> fromSettings,
            java.util.List<org.kendar.llm.harness.settings.AgentSettings> fromProvider) {
        var out = new java.util.LinkedHashMap<String, org.kendar.llm.harness.settings.AgentSettings>();
        if (fromSettings != null) fromSettings.forEach(a -> out.put(a.name(), a));
        fromProvider.forEach(a -> out.put(a.name(), a));
        return java.util.List.copyOf(out.values());
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
                                                                          boolean sharedLocalUsed,
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
        // the role decides (admin-only); the web never falls back because of a setting
        docker.fallbackAllowed(chatId -> roleSandboxOf.apply(chatId).localFallback());
        var runners = new java.util.HashMap<SandboxKind, org.kendar.llm.harness.sandbox.SandboxExecutor>();
        runners.put(SandboxKind.NONE, new DisabledSandboxExecutor(layout));
        runners.put(SandboxKind.DOCKER_ISOLATED, docker);
        runners.put(SandboxKind.DOCKER_OPEN, docker);
        // fail closed: when the backend or a role uses shared-local, no working bwrap on Linux stops the start
        org.kendar.llm.harness.sandbox.SharedLocalSandbox.forHost(layout,
                chatId -> roleSandboxOf.apply(chatId).networkGranted(), sharedLocalUsed)
                .ifPresent(r -> runners.put(SandboxKind.SHARED_LOCAL, r));
        return new RoutedSandboxExecutor(roleSandboxOf, runners, layout);
    }

    /**
     * Whether a missing or broken bwrap stops the start: the shared-local sandbox is used (backend or a role) and
     * {@code jllm.web.allow-missing-bwrap} is not true.
     */
    static boolean sharedLocalRequired(JllmProperties p, boolean backend, boolean anyRole) {
        return (backend || anyRole) && !p.allowMissingBwrap();
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
     * The settings without the stdio MCP servers: the web host never starts one. A stdio server is a child process of
     * the JVM, running on the host as the server's user outside any sandbox, and shared by every user with no identity
     * on its calls. One WARN per server dropped; streamable-HTTP servers are kept.
     */
    public static HarnessSettings withoutStdioMcpServers(HarnessSettings settings) {
        var servers = settings.mcpServers();
        if (servers == null || servers.stream().noneMatch(org.kendar.llm.harness.settings.McpServerSettings::isStdio)) {
            return settings;
        }
        var kept = new java.util.ArrayList<org.kendar.llm.harness.settings.McpServerSettings>();
        for (var m : servers) {
            if (m.isStdio()) {
                LOG.warn("MCP server '{}' is ignored: the web host does not start stdio MCP servers (they would run on "
                        + "the host as the server's user, shared by every user); use a streamable-HTTP server", m.name());
            } else {
                kept.add(m);
            }
        }
        return settings.toBuilder().mcpServers(java.util.List.copyOf(kept)).build();
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

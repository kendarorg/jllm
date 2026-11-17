package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.context.mnemonic.FileMnemonicStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.sandbox.ChatDirSandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.FileChatSessionStore;
import org.kendar.llm.harness.session.HistoryRootLock;
import org.kendar.llm.harness.session.FileHistorySubAgentRunLog;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.settings.FileProjectMcpTrust;
import org.kendar.llm.harness.settings.FileSettingsSource;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.ProjectMcpTrust;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.state.FileStateStore;
import org.kendar.llm.harness.state.StateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires the framework-free {@link AgentHarness} into Spring. */
@Configuration
public class HarnessConfiguration {

    /**
     * The active provider of the application: the {@code jllm.provider} property (a provider of the settings list or a
     * built-in); with {@code apiKey}, {@code baseUrl} or {@code model} set it becomes a session entry that keeps the
     * type but never the per-user authentication of the provider (a credential is not sent to another URL).
     * The harness builds the client through its provider catalog, so it gets the credential resolver.
     *
     * @return the session entry, or null when the properties override nothing
     */
    static org.kendar.llm.harness.llm.ProviderSettings sessionProvider(JllmProperties p,
            org.kendar.llm.harness.settings.HarnessSettings settings) {
        String name = p.providerOrDefault();
        boolean overrides = JllmProperties.blankToNull(p.apiKey()) != null
                || JllmProperties.blankToNull(p.baseUrl()) != null || JllmProperties.blankToNull(p.model()) != null;
        if (!overrides) return null;
        var from = settings.providers().stream().filter(x -> name.equals(x.name())).findFirst().orElse(null);
        String type = from != null ? from.type() : name;
        int maxTokens = from != null && from.maxTokens() != null ? from.maxTokens() : p.maxTokensOrDefault();
        return new org.kendar.llm.harness.llm.ProviderSettings(name, type,
                JllmProperties.blankToNull(p.baseUrl()) != null ? p.baseUrl() : from == null ? null : from.baseUrl(),
                JllmProperties.blankToNull(p.apiKey()) != null ? p.apiKey() : from == null ? null : from.apiKey(),
                JllmProperties.blankToNull(p.model()) != null ? p.model() : from == null ? null : from.model(),
                maxTokens, from == null ? null : from.maxRetries(), from == null ? null : from.timeoutSeconds(),
                from == null ? null : from.requireApiKey(), from == null ? null : from.useMaxCompletionTokens(),
                from == null ? null : from.contextTokens(), from == null ? null : from.smallModel(),
                from == null ? null : from.temperature(), null, from == null ? null : from.thinking());
    }

    @Bean
    public HarnessPricing harnessPricing(JllmProperties p) {
        var pr = p.pricing();
        if (pr == null) return new HarnessPricing(0, 0, 0, 0);
        return new HarnessPricing(nz(pr.inputPerMillion()), nz(pr.outputPerMillion()),
                nz(pr.cacheReadPerMillion()), nz(pr.cacheWritePerMillion()));
    }

    @Bean
    public AgentHarness agentHarness(JllmProperties p, ObjectProvider<ModelClient> modelClient, HarnessPricing pricing,
                                     ObjectMapper mapper, HarnessIdentity identity,
                                     ObjectProvider<SettingsSource> settingsSource,
                                     ObjectProvider<ProjectMcpTrust> projectTrust,
                                     ObjectProvider<ChatSessionStore> sessionStore,
                                     ObjectProvider<SubAgentRunLog> subAgentRunLog,
                                     ObjectProvider<MnemonicStore> mnemonicStore,
                                     ObjectProvider<StateStore> stateStore,
                                     org.kendar.llm.harness.plugin.ServiceRegistry services,
                                     ObjectProvider<org.kendar.llm.harness.plugin.PluginStorages> pluginStorages) {
        String settingsFile = JllmProperties.blankToNull(p.settings());
        Path projectDir = Path.of(p.workdir() == null ? "" : p.workdir()).toAbsolutePath().normalize();
        // default: global ~/.jllm/settings.json, then project .jllm/settings.json, then the settings file; later wins.
        // An application keeping the layers elsewhere (a database) supplies a SettingsSource bean, and where the trust
        // answers are remembered with a ProjectMcpTrust bean.
        SettingsSource source = settingsSource.getIfAvailable(() ->
                new FileSettingsSource(settingsFile == null ? null : Path.of(settingsFile), projectDir));
        HarnessSettings settings = HarnessSettings.loadLayered(source, projectDir, System::getenv,
                ProjectMcpTrust.Prompt.NONE, projectTrust.getIfAvailable(FileProjectMcpTrust::user),
                org.slf4j.LoggerFactory.getLogger(HarnessConfiguration.class)::warn);
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
        if (p.contextTokens() != null && p.contextTokens() > 0) settings = settings.withMaxTokens(p.contextTokens());
        if (JllmProperties.blankToNull(p.contextStrategy()) != null) {
            settings = settings.withContextStrategy(p.contextStrategy());
        }
        if (Boolean.TRUE.equals(p.smallModel())) settings = settings.withSmallModel(true);
        if (JllmProperties.blankToNull(p.sandbox()) != null) settings = settings.withSandboxBackend(p.sandbox());
        if (JllmProperties.blankToNull(p.dockerImage()) != null) settings = settings.withDockerImage(p.dockerImage());
        if (JllmProperties.blankToNull(p.dockerMode()) != null) settings = settings.withDockerMode(p.dockerMode());
        if (p.dockerRemote() != null || JllmProperties.blankToNull(p.dockerRemoteHost()) != null) {
            settings = settings.withDockerRemote(p.dockerRemote(), JllmProperties.blankToNull(p.dockerRemoteHost()));
        }
        if (p.plugins() != null) {
            for (var e : p.plugins().entrySet()) {
                if (e.getValue() == null) continue;
                settings = settings.withPlugin(e.getKey(), e.getValue().enabled(),
                        e.getValue().settings() == null ? null : mapper.valueToTree(e.getValue().settings()));
            }
        }
        services.publish(org.kendar.llm.web.commons.plugins.PluginsSettings.class,
                new org.kendar.llm.web.commons.plugins.PluginsSettings(settings.plugins()));
        HarnessConfig config = settings.applyTo(HarnessConfig.defaults());
        String searchKey = JllmProperties.blankToNull(p.searchApiKey());
        if (searchKey == null) searchKey = System.getenv("BRAVE_API_KEY");
        if (searchKey != null) config = config.withSearchApiKey(searchKey);
        if (p.maxTurns() != null && p.maxTurns() > 0) config = config.withMaxTurns(p.maxTurns());
        config = config.withMaxTokens(p.maxTokensOrDefault());

        ChatDirSandboxExecutor sandbox = new ChatDirSandboxExecutor(config, projectDir);
        HistoryRootLock.acquire(sandbox.location()); // one process per base directory; held until the process ends
        // plugins find the host's plugin storage in the shared registry (files unless the application supplies another)
        services.publish(org.kendar.llm.harness.plugin.PluginStorages.class, pluginStorages.getIfAvailable(() ->
                new org.kendar.llm.web.commons.plugins.FilePluginStorage(
                        projectDir.resolve(".jllm").resolve("plugin-data"), mapper)));
        AgentHarness.Builder builder = AgentHarness.builder().services(services);
        ModelClient supplied = modelClient.getIfAvailable();
        if (supplied != null) {
            builder.modelClient(supplied); // an application that brings its own client
        } else {
            var session = sessionProvider(p, settings);
            builder.activeProvider(session != null ? org.kendar.llm.harness.llm.ProviderCatalog.CUSTOM : p.providerOrDefault())
                    .sessionProvider(session);
        }
        if (JllmProperties.blankToNull(p.pluginDir()) != null) builder.pluginDir(Path.of(p.pluginDir()));
        else if (java.nio.file.Files.isDirectory(Path.of("plugins"))) builder.pluginDir(Path.of("plugins"));
        AgentHarness harness = builder
                .config(config)
                .systemPrompt(promptAppend)
                .systemPromptReplace(promptReplace)
                .sandbox(executor(settings, config, sandbox, projectDir))
                // chat history: files under each chat's history root, unless the application supplies the beans
                .sessionStore(sessionStore.getIfAvailable(() ->
                        new FileChatSessionStore(sandbox::historyRoot, () -> chatHistoryRoots(sandbox), mapper)))
                .subAgentRunLog(subAgentRunLog.getIfAvailable(() ->
                        new FileHistorySubAgentRunLog(new InMemorySubAgentRunLog(), sandbox::historyRoot)))
                .mnemonicStore(mnemonicStore.getIfAvailable(() -> new FileMnemonicStore(sandbox::historyRoot)))
                .stateStore(stateStore.getIfAvailable(() -> new FileStateStore(sandbox::historyRoot)))
                .settings(settings)
                .pricing(pricing)
                .objectMapper(mapper)
                .credentialResolver(identity.credentials())
                .userPolicyResolver(identity.policies())
                .principalDirectory(identity.principals())
                .tokenStoreDir(identity.tokenDir())
                .tokenStore(identity.tokenStore())
                .discoverPlugins()
                .build();
        harness.enforceChatRetention(settings.chatRetentionLimit(), null);
        return harness;
    }

    /** {@code <base>/<chatId>/.jllm/history} of every chat directory below the base. */
    private static java.util.List<Path> chatHistoryRoots(ChatDirSandboxExecutor sandbox) {
        Path base = sandbox.location();
        if (!java.nio.file.Files.isDirectory(base)) return java.util.List.of();
        try (var s = java.nio.file.Files.list(base)) {
            return s.filter(java.nio.file.Files::isDirectory)
                    .map(d -> sandbox.historyRoot(d.getFileName().toString()))
                    .filter(java.nio.file.Files::isDirectory).toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Local, docker one command per container, or docker daemon mode (one long-running container per chat). */
    private static org.kendar.llm.harness.sandbox.SandboxExecutor executor(HarnessSettings settings, HarnessConfig config,
                                                                          ChatDirSandboxExecutor layout, Path projectDir) {
        var runner = new org.kendar.llm.harness.sandbox.ProcessDockerCommandRunner();
        if (!settings.dockerSandbox()) return layout;
        if (settings.dockerDaemon()) {
            return new org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor(config, runner, layout,
                    org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.Kind.WEB, projectDir);
        }
        return new org.kendar.llm.harness.sandbox.DockerSandboxExecutor(config, runner, layout);
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }
}

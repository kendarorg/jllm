package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.context.mnemonic.FileMnemonicStore;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelClientFactory;
import org.kendar.llm.harness.sandbox.ChatDirSandboxExecutor;
import org.kendar.llm.harness.session.FileHistoryChatSessionStore;
import org.kendar.llm.harness.session.FileHistorySubAgentRunLog;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires the framework-free {@link AgentHarness} into Spring. */
@Configuration
public class HarnessConfiguration {

    @Bean
    public ModelClient modelClient(JllmProperties p, ObjectMapper mapper) {
        return ModelClientFactory.create(p.providerOrDefault(), p.apiKey(), p.baseUrl(), p.model(),
                p.maxTokensOrDefault(), mapper);
    }

    @Bean
    public HarnessPricing harnessPricing(JllmProperties p) {
        var pr = p.pricing();
        if (pr == null) return new HarnessPricing(0, 0, 0, 0);
        return new HarnessPricing(nz(pr.inputPerMillion()), nz(pr.outputPerMillion()),
                nz(pr.cacheReadPerMillion()), nz(pr.cacheWritePerMillion()));
    }

    @Bean
    public AgentHarness agentHarness(JllmProperties p, ModelClient modelClient, HarnessPricing pricing,
                                     ObjectMapper mapper) {
        String settingsFile = JllmProperties.blankToNull(p.settingsFile());
        HarnessSettings settings = settingsFile == null ? HarnessSettings.empty()
                : HarnessSettings.load(Path.of(settingsFile));
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
        HarnessConfig config = settings.applyTo(HarnessConfig.defaults());
        String searchKey = JllmProperties.blankToNull(p.searchApiKey());
        if (searchKey == null) searchKey = System.getenv("BRAVE_API_KEY");
        if (searchKey != null) config = config.withSearchApiKey(searchKey);
        if (p.maxTurns() != null && p.maxTurns() > 0) config = config.withMaxTurns(p.maxTurns());
        config = config.withMaxTokens(p.maxTokensOrDefault());

        Path projectDir = Path.of(p.workdir() == null ? "" : p.workdir()).toAbsolutePath().normalize();
        ChatDirSandboxExecutor sandbox = new ChatDirSandboxExecutor(config, projectDir);
        return AgentHarness.builder()
                .modelClient(modelClient)
                .config(config)
                .systemPrompt(JllmProperties.blankToNull(p.systemPrompt()))
                .sandbox(executor(settings, config, sandbox, projectDir))
                .sessionStore(new FileHistoryChatSessionStore(new InMemoryChatSessionStore(), sandbox::historyRoot))
                .subAgentRunLog(new FileHistorySubAgentRunLog(new InMemorySubAgentRunLog(), sandbox::historyRoot))
                .mnemonicStore(new FileMnemonicStore(sandbox::historyRoot))
                .stateStore(new org.kendar.llm.harness.state.FileStateStore(sandbox::historyRoot))
                .settings(settings)
                .pricing(pricing)
                .objectMapper(mapper)
                .discoverPlugins()
                .build();
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

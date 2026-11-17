package org.kendar.llm.cli;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.util.Map;

/** Command line flags that win over the settings files. */
final class SettingsOverrides {

    /** Default output limit of one response, all providers. */
    static final int DEFAULT_MAX_TOKENS = 32_000;

    private SettingsOverrides() {
    }

    static HarnessSettings applyToSettings(Map<String, String> a, HarnessSettings settings) {
        if (a.containsKey("context-tokens") && Integer.parseInt(a.get("context-tokens")) > 0) {
            settings = settings.withMaxTokens(Integer.parseInt(a.get("context-tokens"))); // <= 0: default, as the web app
        }
        if (a.containsKey("context-strategy")) settings = settings.withContextStrategy(a.get("context-strategy"));
        if (CliArgs.flag(a, "small-model")) settings = settings.withSmallModel(true);
        if (a.containsKey("sandbox")) settings = settings.withSandboxBackend(a.get("sandbox"));
        if (a.containsKey("docker-image")) settings = settings.withDockerImage(a.get("docker-image"));
        if (a.containsKey("docker-mode")) settings = settings.withDockerMode(a.get("docker-mode"));
        if (CliArgs.flag(a, "docker-remote") || a.containsKey("docker-remote-host")) {
            settings = settings.withDockerRemote(CliArgs.flag(a, "docker-remote") ? Boolean.TRUE : null,
                    a.get("docker-remote-host"));
        }
        for (var e : a.entrySet()) { // --plugins.<id>.enabled=true: this run only, as jllm.plugins.* in the web app
            if (e.getKey().startsWith("plugins.")) settings = settings.withPluginOption(e.getKey().substring(8), e.getValue());
        }
        return settings;
    }

    /** @param envSearchKey the BRAVE_API_KEY of the environment, used when {@code --search-api-key} is absent */
    static HarnessConfig applyToConfig(Map<String, String> a, HarnessConfig config, String envSearchKey) {
        String searchKey = a.getOrDefault("search-api-key", envSearchKey);
        if (searchKey != null) config = config.withSearchApiKey(searchKey);
        if (a.containsKey("max-turns") && Integer.parseInt(a.get("max-turns")) > 0) {
            config = config.withMaxTurns(Integer.parseInt(a.get("max-turns")));
        }
        return config.withMaxTokens(a.containsKey("max-tokens") ? Integer.parseInt(a.get("max-tokens"))
                : DEFAULT_MAX_TOKENS);
    }
}

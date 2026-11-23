package org.kendar.llm.cli;

import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.ExternalLinks;

import java.util.Map;

/** Command line flags that win over the settings files. */
final class SettingsOverrides {

    /** Default output limit of one response, all providers. */
    static final int DEFAULT_MAX_TOKENS = 32_000;

    private SettingsOverrides() {
    }

    static HarnessSettings applyToSettings(Map<String, String> a, HarnessSettings settings) {
        var b = settings.toBuilder();
        if (a.containsKey("context-tokens") && Integer.parseInt(a.get("context-tokens")) > 0) {
            b.maxTokens(Integer.parseInt(a.get("context-tokens"))); // <= 0: default, as the web app
        }
        if (a.containsKey("context-strategy")) b.contextStrategy(a.get("context-strategy"));
        if (CliArgs.flag(a, "small-model")) b.smallModel(true);
        if (a.containsKey("sandbox")) b.sandboxBackend(a.get("sandbox"));
        if (a.containsKey("docker-image")) b.dockerImage(a.get("docker-image"));
        if (a.containsKey("docker-mode")) b.dockerMode(a.get("docker-mode"));
        // what the file tools do with a link that leaves the project: none, read (default) or readwrite
        if (a.containsKey("external-links")) {
            b.externalLinks(ExternalLinks.parse(a.get("external-links"), ExternalLinks.DEFAULT));
        }
        if (CliArgs.flag(a, "docker-remote") || a.containsKey("docker-remote-host")) {
            b.dockerRemote(CliArgs.flag(a, "docker-remote") ? Boolean.TRUE : null, a.get("docker-remote-host"));
        }
        settings = b.build();
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

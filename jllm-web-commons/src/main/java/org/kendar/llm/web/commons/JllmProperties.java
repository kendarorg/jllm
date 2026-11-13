package org.kendar.llm.web.commons;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jllm.*} settings.
 *
 * @param provider      anthropic | openai | gemini | grok | qwen | llamacpp | ollama
 * @param apiKey        API key; empty falls back to the {@code <PROVIDER>_API_KEY} environment variable
 * @param baseUrl       endpoint override; empty = provider default
 * @param model         model override; empty = provider default
 * @param maxTokens     output limit of one response
 * @param contextTokens model context window; null/0 = harness default
 * @param maxTurns      model calls per user turn; null/0 = harness default
 * @param workdir       project directory the agent works in (blank = current directory)
 * @param settings      optional harness settings file (MCP servers, skills, agents ...)
 * @param contextStrategy compaction | mnemonic; wins over the settings file
 * @param smallModel    append the small-model rules to the system prompt
 * @param systemPrompt  text appended to the built-in system prompt
 * @param systemPromptFile file appended to the system prompt
 * @param systemPromptReplace file that replaces the built-in system prompt
 * @param searchApiKey  Brave key for web_search
 * @param pricing       USD per million tokens, for the cost estimate
 * @param sandbox       local | docker; wins over the settings file
 * @param dockerImage   image of the docker sandbox; wins over the settings file
 * @param dockerMode    oneshot | daemon: a throwaway container per command, or one long-running container per chat
 * @param dockerRemote  daemon mode with a docker daemon on another machine: the workspace is uploaded and backed up
 * @param dockerRemoteHost address at which the published ports of the remote docker host are reached
 * @param pluginDir     directory of plugin jars; blank = {@code ./plugins} when it exists
 * @param plugins       {@code jllm.plugins.<id>.enabled} and {@code jllm.plugins.<id>.settings.*}; same as the
 *                      {@code plugins} section of the settings file, and wins over it
 */
@ConfigurationProperties("jllm")
public record JllmProperties(String provider, String apiKey, String baseUrl, String model, Integer maxTokens,
                             Integer contextTokens, Integer maxTurns, String workdir, String settings,
                             String contextStrategy, Boolean smallModel, String systemPrompt, String systemPromptFile, String systemPromptReplace, String searchApiKey, Pricing pricing,
                             String sandbox, String dockerImage, String dockerMode,
                             Boolean dockerRemote, String dockerRemoteHost, String pluginDir,
                             java.util.Map<String, Plugin> plugins) {

    /** One entry of {@code jllm.plugins}. */
    public record Plugin(Boolean enabled, java.util.Map<String, Object> settings) {
    }

    public record Pricing(Double inputPerMillion, Double outputPerMillion, Double cacheReadPerMillion,
                          Double cacheWritePerMillion) {
    }

    public String providerOrDefault() {
        return provider == null || provider.isBlank() ? "anthropic" : provider.trim().toLowerCase();
    }

    public int maxTokensOrDefault() {
        return maxTokens == null || maxTokens <= 0 ? 32_000 : maxTokens;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

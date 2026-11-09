package org.kendar.llm.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jllm.*} settings.
 *
 * @param provider      anthropic | openai | gemini | qwen | llamacpp | ollama
 * @param apiKey        API key; empty falls back to the {@code <PROVIDER>_API_KEY} environment variable
 * @param baseUrl       endpoint override; empty = provider default
 * @param model         model override; empty = provider default
 * @param maxTokens     output limit of one response
 * @param contextTokens model context window; null/0 = harness default
 * @param maxTurns      model calls per user turn; null/0 = harness default
 * @param workdir       project directory the agent works in (blank = current directory)
 * @param settingsFile  optional harness settings file (MCP servers, skills, agents ...)
 * @param contextStrategy compaction | mnemonic; wins over the settings file
 * @param smallModel    append the small-model rules to the system prompt
 * @param systemPrompt  text appended to the built-in system prompt
 * @param searchApiKey  Brave key for web_search
 * @param pricing       USD per million tokens, for the cost estimate
 * @param sandbox       local | docker; wins over the settings file
 * @param dockerImage   image of the docker sandbox; wins over the settings file
 * @param dockerMode    oneshot | daemon: a throwaway container per command, or one long-running container per chat
 * @param dockerRemote  daemon mode with a docker daemon on another machine: the workspace is uploaded and backed up
 * @param dockerRemoteHost address at which the published ports of the remote docker host are reached
 */
@ConfigurationProperties("jllm")
public record JllmProperties(String provider, String apiKey, String baseUrl, String model, Integer maxTokens,
                             Integer contextTokens, Integer maxTurns, String workdir, String settingsFile,
                             String contextStrategy, Boolean smallModel, String systemPrompt, String searchApiKey, Pricing pricing,
                             String sandbox, String dockerImage, String dockerMode,
                             Boolean dockerRemote, String dockerRemoteHost) {

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

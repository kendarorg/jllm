package org.kendar.llm.web.commons;

/**
 * {@code jllm.*} settings; bound from the application's configuration by the web framework.
 *
 * @param provider      anthropic | openai | openaiGpt62 | gemini | grok | qwen | llamacpp | ollama
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
 * @param web           {@code jllm.web.public-url}: the externally visible base URL (OAuth logins redirect to
 *                      {@code <public-url>/oauth/callback});
 *                      {@code jllm.web.use-real-password}: random passwords instead of the default ones
 */
public record JllmProperties(String provider, String apiKey, String baseUrl, String model, Integer maxTokens,
                             Integer contextTokens, Integer maxTurns, String workdir, String settings,
                             String contextStrategy, Boolean smallModel, String systemPrompt, String systemPromptFile, String systemPromptReplace, String searchApiKey, Pricing pricing,
                             String sandbox, String dockerImage, String dockerMode,
                             Boolean dockerRemote, String dockerRemoteHost, String pluginDir,
                             java.util.Map<String, Plugin> plugins, Web web) {

    /**
     * @param publicUrl       externally visible base URL, e.g. {@code https://jllm.example.com}
     * @param useRealPassword at startup, every user still on the default password gets a random one; the admin's is
     *                        written to the log (once). Default false: the demo users keep their default password
     * @param enforcePasswordChangeOnLoopback while a user is on the default password, the API refuses everything but
     *                        the password change, sign-in and sign-out ({@code 403 password_change_required}). Requests
     *                        from the loopback address are exempt unless this is true. Default false
     * @param contentSecurityPolicy {@code Content-Security-Policy} header value; blank = the built-in policy, the
     *                        literal {@code off} = no CSP header. {@code X-Content-Type-Options} and
     *                        {@code Referrer-Policy} are always set
     */
    public record Web(String publicUrl, Boolean useRealPassword, Boolean enforcePasswordChangeOnLoopback,
                      String contentSecurityPolicy) {
        public boolean realPasswords() {
            return Boolean.TRUE.equals(useRealPassword);
        }

        public boolean enforceOnLoopback() {
            return Boolean.TRUE.equals(enforcePasswordChangeOnLoopback);
        }
    }

    /** One entry of {@code jllm.plugins}. */
    public record Plugin(Boolean enabled, java.util.Map<String, Object> settings) {
    }

    public record Pricing(Double inputPerMillion, Double outputPerMillion, Double cacheReadPerMillion,
                          Double cacheWritePerMillion) {
    }

    /** Nothing set: every key at its default. */
    public static JllmProperties defaults() {
        return new JllmProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public String providerOrDefault() {
        return provider == null || provider.isBlank() ? "anthropic" : provider.trim().toLowerCase();
    }

    public int maxTokensOrDefault() {
        return maxTokens == null || maxTokens <= 0 ? 32_000 : maxTokens;
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

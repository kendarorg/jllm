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
 * @param chatsDir      directory holding the chat directories (blank = {@code chats} in the current directory). Users
 *                      write there: no server file (webauth, database, configuration, plugins, flows) may be inside
 * @param contextStrategy compaction | mnemonic; wins over the settings file
 * @param smallModel    append the small-model rules to the system prompt
 * @param systemPrompt  text appended to the built-in system prompt
 * @param searchApiKey  Brave key for web_search
 * @param pricing       USD per million tokens, for the cost estimate
 * @param sandbox       local | docker; wins over the settings file
 * @param dockerImage   image of the docker sandbox; wins over the settings file
 * @param dockerScope   chat | user: one container per chat (removed with the chat) or one per user; the admin decides
 * @param dockerProfile standard | hardened container profile (hardened: no network by default, limits)
 * @param dockerRemote  a a docker daemon on another machine: the workspace is uploaded and backed up
 * @param dockerRemoteHost address at which the published ports of the remote docker host are reached
 * @param pluginDir     directory of plugin jars; blank = {@code ./plugins} when it exists
 * @param plugins       {@code jllm.plugins.<id>.enabled} and {@code jllm.plugins.<id>.settings.*}; same as the
 *                      {@code plugins} section of the settings file, and wins over it
 * @param web           {@code jllm.web.public-url}: the externally visible base URL (OAuth logins redirect to
 *                      {@code <public-url>/oauth/callback});
 *                      {@code jllm.web.use-real-password}: random passwords instead of the default ones
 */
public record JllmProperties(String provider, String apiKey, String baseUrl, String model, Integer maxTokens,
                             Integer contextTokens, Integer maxTurns, String chatsDir,
                             String contextStrategy, Boolean smallModel, String systemPrompt, String searchApiKey, Pricing pricing,
                             String sandbox, String dockerImage, String dockerScope, String dockerProfile,
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
     * @param allowMissingBwrap {@code jllm.web.allow-missing-bwrap}: on Linux without a working {@code bwrap} the
     *                        server refuses to start when the shared-local sandbox is in use. With true it logs the
     *                        error and starts; shared-local roles then get no sandbox and their tool calls error.
     *                        Default false
     * @param trustedProxies  {@code jllm.web.trusted-proxies}: addresses or CIDRs of reverse proxies whose
     *                        {@code X-Forwarded-For} is believed for the sign-in throttle. Default none: the header is
     *                        ignored, and behind a proxy every caller counts as the proxy's address
     * @param sessionMaxAgeDays {@code jllm.web.session-max-age-days}: absolute lifetime of a sign-in session, whatever
     *                        its use; the 7-day sliding expiry applies within it. Default 30
     */
    public record Web(String publicUrl, Boolean useRealPassword, Boolean enforcePasswordChangeOnLoopback,
                      String contentSecurityPolicy, Boolean allowMissingBwrap, java.util.List<String> trustedProxies,
                      Integer sessionMaxAgeDays) {
        /** Default of {@link #sessionMaxAgeDays}. */
        public static final int DEFAULT_SESSION_MAX_AGE_DAYS = 30;

        public Web {
            trustedProxies = trustedProxies == null ? java.util.List.of() : java.util.List.copyOf(trustedProxies);
        }

        /** The absolute lifetime of a session. */
        public java.time.Duration sessionMaxAge() {
            return java.time.Duration.ofDays(sessionMaxAgeDays == null || sessionMaxAgeDays <= 0
                    ? DEFAULT_SESSION_MAX_AGE_DAYS : sessionMaxAgeDays);
        }

        public boolean realPasswords() {
            return Boolean.TRUE.equals(useRealPassword);
        }

        public boolean missingBwrapAllowed() {
            return Boolean.TRUE.equals(allowMissingBwrap);
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
                null, null, null, null, null, null, null, null);
    }

    /** The absolute session lifetime, whatever {@link #web} holds. */
    public java.time.Duration sessionMaxAge() {
        return web == null ? java.time.Duration.ofDays(Web.DEFAULT_SESSION_MAX_AGE_DAYS) : web.sessionMaxAge();
    }

    /** {@code jllm.web.trusted-proxies}, default none. */
    public java.util.List<String> trustedProxies() {
        return web == null ? java.util.List.of() : web.trustedProxies();
    }

    /** Whether a missing bwrap only logs an error ({@code jllm.web.allow-missing-bwrap}). */
    public boolean allowMissingBwrap() {
        return web != null && web.missingBwrapAllowed();
    }

    /** Folder of the chats when {@code jllm.chats-dir} is not set. */
    public static final String DEFAULT_CHATS_DIR = "chats";

    /** The canonical-looking (absolute, normalized) chats directory. */
    public java.nio.file.Path chatsPath() {
        return java.nio.file.Path.of(blankToNull(chatsDir) == null ? DEFAULT_CHATS_DIR : chatsDir).toAbsolutePath().normalize();
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

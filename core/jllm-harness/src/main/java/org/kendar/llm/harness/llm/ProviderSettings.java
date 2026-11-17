package org.kendar.llm.harness.llm;

import org.kendar.llm.harness.settings.OAuthSettings;

import java.util.Locale;
import java.util.Map;

/**
 * A named model provider from the settings {@code providers} list. A superset of {@link LlmSettings}; unset fields
 * take the defaults of the {@code type}.
 *
 * @param name           descriptive name used by {@code --provider}, {@code /model} and agent settings
 * @param type           {@code anthropic}, or an OpenAI-compatible kind ({@code openai}, {@code qwen},
 *                       {@code llamacpp}, {@code ollama}); default: the name
 * @param contextTokens  context window of the model; used by the fit check when switching models
 * @param smallModel     the model is small: the system prompt gets the stricter small-model rules
 * @param temperature    sampling temperature (0 to 2; above 1 is ignored for Anthropic); null = not sent
 * @param thinking       default reasoning effort (minimal | low | medium | high); null = the client's own default
 * @param order          fallback order: when a provider fails, the providers with a higher order are tried in
 *                       ascending order; null = never used as a fallback
 * @param stream         stream the responses of the agent loop (default true); false for servers whose streaming
 *                       misbehaves. Helper calls ({@code simpleComplete}) never stream
 * @param userAuth       {@code token} (each user brings their own key), {@code oauth} (each user logs in) or null
 *                       (the global key only, the default)
 * @param oauth          required for {@code userAuth: oauth}, rejected otherwise
 * @param allowGlobalFallback a user without their own credential may use the global key (default false; needs a
 *                       global key)
 * @param headers        extra static request headers (e.g. {@code anthropic-beta} for Anthropic OAuth)
 * @param fast           this provider serves the helper calls (summaries, classifiers, tool selection); at most one
 */
public record ProviderSettings(String name, String type, String baseUrl, String apiKey, String model,
                               Integer maxTokens, Integer maxRetries, Integer timeoutSeconds,
                               Boolean requireApiKey, Boolean useMaxCompletionTokens, Integer contextTokens,
                               Boolean smallModel, Double temperature, Integer order, ThinkingLevel thinking,
                               Boolean stream, String userAuth, OAuthSettings oauth, Boolean allowGlobalFallback,
                               Map<String, String> headers, Boolean fast) {

    public static final String USER_TOKEN = "token";
    public static final String USER_OAUTH = "oauth";

    /** Constructor without the authentication fields: the global key only. */
    public ProviderSettings(String name, String type, String baseUrl, String apiKey, String model,
                            Integer maxTokens, Integer maxRetries, Integer timeoutSeconds,
                            Boolean requireApiKey, Boolean useMaxCompletionTokens, Integer contextTokens,
                            Boolean smallModel, Double temperature, Integer order, ThinkingLevel thinking) {
        this(name, type, baseUrl, apiKey, model, maxTokens, maxRetries, timeoutSeconds, requireApiKey,
                useMaxCompletionTokens, contextTokens, smallModel, temperature, order, thinking, null, null, null,
                null, null, null);
    }

    /** False only when {@code stream} is set to false. */
    public boolean streams() {
        return !Boolean.FALSE.equals(stream);
    }

    /** Legacy constructor without a thinking level. */
    public ProviderSettings(String name, String type, String baseUrl, String apiKey, String model,
                            Integer maxTokens, Integer maxRetries, Integer timeoutSeconds,
                            Boolean requireApiKey, Boolean useMaxCompletionTokens, Integer contextTokens,
                            Boolean smallModel, Double temperature, Integer order) {
        this(name, type, baseUrl, apiKey, model, maxTokens, maxRetries, timeoutSeconds, requireApiKey,
                useMaxCompletionTokens, contextTokens, smallModel, temperature, order, null);
    }

    /** Legacy constructor without a fallback order. */
    public ProviderSettings(String name, String type, String baseUrl, String apiKey, String model,
                            Integer maxTokens, Integer maxRetries, Integer timeoutSeconds,
                            Boolean requireApiKey, Boolean useMaxCompletionTokens, Integer contextTokens,
                            Boolean smallModel, Double temperature) {
        this(name, type, baseUrl, apiKey, model, maxTokens, maxRetries, timeoutSeconds, requireApiKey,
                useMaxCompletionTokens, contextTokens, smallModel, temperature, null);
    }

    public ProviderSettings {
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Provider '" + name + "': temperature must be between 0 and 2");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (userAuth != null && !userAuth.equals(USER_TOKEN) && !userAuth.equals(USER_OAUTH)) {
            throw new IllegalArgumentException("Provider '" + name + "': userAuth must be token or oauth, not '"
                    + userAuth + "'");
        }
        if (USER_OAUTH.equals(userAuth)) {
            if (oauth == null) {
                throw new IllegalArgumentException("Provider '" + name + "': userAuth oauth needs an oauth block");
            }
            oauth.validate("Provider '" + name + "'");
            if (oauth.clientCredentials()) {
                throw new IllegalArgumentException("Provider '" + name
                        + "': oauth client_credentials is a shared login, not a per-user one (not supported yet)");
            }
        } else if (oauth != null) {
            throw new IllegalArgumentException("Provider '" + name + "': the oauth block needs userAuth oauth");
        }
        if (Boolean.TRUE.equals(allowGlobalFallback)) {
            if (userAuth == null) {
                throw new IllegalArgumentException("Provider '" + name + "': allowGlobalFallback needs userAuth");
            }
            if (!hasGlobalKey(type, name, apiKey)) {
                throw new IllegalArgumentException("Provider '" + name
                        + "': allowGlobalFallback needs a global key (apiKey or the <TYPE>_API_KEY variable)");
            }
        }
    }

    private static boolean hasGlobalKey(String type, String name, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) return true;
        String t = type != null && !type.isBlank() ? type : name;
        String v = t == null ? null : System.getenv(t.trim().toUpperCase(Locale.ROOT) + "_API_KEY");
        return v != null && !v.isBlank();
    }

    /** The source name of the per-user credential: {@code provider:<name>}. */
    public String source() {
        return "provider:" + name;
    }

    public boolean perUser() {
        return userAuth != null;
    }

    public boolean fallsBackToGlobal() {
        return Boolean.TRUE.equals(allowGlobalFallback);
    }

    public boolean isFast() {
        return Boolean.TRUE.equals(fast);
    }

    /** The global key: {@code apiKey}, else {@code <TYPE>_API_KEY}; empty when there is none. */
    public String globalKey() {
        String k = toLlmSettings().apiKey();
        return k == null ? "" : k;
    }

    /** The effective type: {@code type}, or the name when no type is given. */
    public String effectiveType() {
        String t = type != null && !type.isBlank() ? type : name;
        return t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
    }

    /** Connection settings for {@link ModelClients#from}; the client is picked by the type. */
    public LlmSettings toLlmSettings() {
        String t = effectiveType();
        boolean local = t.equals("ollama") || t.equals("llamacpp");
        String defUrl = switch (t) {
            case "anthropic" -> "https://api.anthropic.com";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "grok" -> "https://api.x.ai/v1";
            case "qwen" -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
            case "ollama" -> ModelClients.OLLAMA_URL;
            case "llamacpp" -> "http://localhost:8080/v1";
            default -> "https://api.openai.com/v1";
        };
        String defModel = switch (t) {
            case "anthropic" -> AnthropicSettings.defaults(null).model();
            case "gemini" -> "gemini-2.5-pro";
            case "grok" -> "grok-4";
            case "qwen" -> "qwen3-coder-plus";
            case "ollama" -> ModelClients.OLLAMA_DEFAULT_MODEL;
            case "llamacpp" -> "local";
            default -> "gpt-4.1";
        };
        String key = apiKey != null && !apiKey.isBlank() ? apiKey : System.getenv(t.toUpperCase(Locale.ROOT) + "_API_KEY");
        return new LlmSettings(t, baseUrl != null ? baseUrl : defUrl, key == null ? "" : key,
                model != null ? model : defModel, maxTokens != null ? maxTokens : 8192,
                maxRetries != null ? maxRetries : local ? 2 : 5, timeoutSeconds != null ? timeoutSeconds : local ? 1800 : 600,
                requireApiKey != null ? requireApiKey : !local,
                useMaxCompletionTokens != null ? useMaxCompletionTokens : t.equals("openai"));
    }
}

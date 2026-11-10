package org.kendar.llm.harness.llm;

import java.util.Locale;

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
 */
public record ProviderSettings(String name, String type, String baseUrl, String apiKey, String model,
                               Integer maxTokens, Integer maxRetries, Integer timeoutSeconds,
                               Boolean requireApiKey, Boolean useMaxCompletionTokens, Integer contextTokens,
                               Boolean smallModel, Double temperature, Integer order, ThinkingLevel thinking) {

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

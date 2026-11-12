package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Builds the {@link ModelClient} for a provider name, shared by every front end (CLI, web).
 * Providers: anthropic, openai, gemini, grok, qwen, llamacpp, ollama. Null/blank arguments fall back to the provider defaults.
 */
public final class ModelClientFactory {

    private ModelClientFactory() {
    }

    /**
     * @param provider  provider name; null/blank = anthropic
     * @param apiKey    API key; null/blank falls back to the {@code <PROVIDER>_API_KEY} environment variable
     * @param baseUrl   endpoint override
     * @param model     model override
     * @param maxTokens output limit of one response
     */
    public static ModelClient create(String provider, String apiKey, String baseUrl, String model, int maxTokens,
                                     ObjectMapper mapper) {
        String p = provider == null || provider.isBlank() ? "anthropic" : provider.trim().toLowerCase(Locale.ROOT);
        String key = blank(apiKey) ? System.getenv(p.toUpperCase(Locale.ROOT) + "_API_KEY") : apiKey;
        if (p.equals("anthropic")) {
            AnthropicSettings d = AnthropicSettings.defaults(key);
            return ModelClients.anthropic(new AnthropicSettings(key, blank(baseUrl) ? d.baseUrl() : baseUrl,
                    blank(model) ? d.model() : model, maxTokens, d.maxRetries(), d.cacheTtl(),
                    d.thinkingBudget(), d.timeoutSeconds(), d.citations()), mapper);
        }
        LlmSettings s = switch (p) {
            case "openai" -> new LlmSettings("openai", "https://api.openai.com/v1", key, "gpt-4.1", 8192, 5, 600,
                    true, true);
            case "gemini" -> new LlmSettings("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", key,
                    "gemini-2.5-pro", 8192, 5, 600, true, false);
            case "grok" -> new LlmSettings("grok", "https://api.x.ai/v1", key,
                    "grok-4", 8192, 5, 600, true, false);
            case "qwen" -> new LlmSettings("qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", key,
                    "qwen3-coder-plus", 8192, 5, 600, true, false);
            case "ollama" -> ModelClients.ollamaSettings(ModelClients.OLLAMA_URL, ModelClients.OLLAMA_DEFAULT_MODEL);
            case "llamacpp" -> new LlmSettings("llamacpp", "http://localhost:8080/v1", key == null ? "" : key,
                    "local", 8192, 2, 1800, false, false);
            default -> throw new IllegalArgumentException("Unknown provider '" + p
                    + "' (expected anthropic | openai | gemini | grok | qwen | llamacpp | ollama)");
        };
        return ModelClients.from(new LlmSettings(s.name(), blank(baseUrl) ? s.baseUrl() : baseUrl, s.apiKey(),
                blank(model) ? s.model() : model, maxTokens, s.maxRetries(), s.timeoutSeconds(),
                s.requireApiKey(), s.useMaxCompletionTokens()), mapper);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

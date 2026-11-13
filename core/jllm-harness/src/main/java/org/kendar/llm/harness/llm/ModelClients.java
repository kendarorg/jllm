package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/** Factory of the built-in {@link ModelClient}s. */
public final class ModelClients {

    private ModelClients() {
    }

    /**
     * The client for {@code settings.name()}: {@code anthropic}, or any other name for an OpenAI-compatible
     * endpoint (openai, qwen, llamacpp, ollama, ...). For {@code anthropic} the connection fields are taken from
     * the settings and the rest from {@link AnthropicSettings#defaults}.
     */
    public static ModelClient from(LlmSettings settings, ObjectMapper mapper) {
        String name = settings.name() == null ? "" : settings.name().trim().toLowerCase(Locale.ROOT);
        if (name.equals("anthropic")) {
            AnthropicSettings d = AnthropicSettings.defaults(settings.apiKey());
            return new AnthropicModelClient(new AnthropicSettings(settings.apiKey(), settings.baseUrl(),
                    settings.model(), settings.maxTokens(), settings.maxRetries(), d.cacheTtl(), d.thinkingBudget(),
                    settings.timeoutSeconds(), d.citations()), mapper);
        }
        return new OpenAiCompatModelClient(settings, mapper);
    }

    /** Client for a role: FAST uses {@code fast} when given, otherwise (and for MAIN) {@code main}. */
    public static ModelClient forRole(ModelRole role, LlmSettings main, LlmSettings fast, ObjectMapper mapper) {
        return from(role == ModelRole.FAST && fast != null ? fast : main, mapper);
    }

    /** Ollama's OpenAI-compatible endpoint at http://localhost:11434/v1; no API key. */
    public static ModelClient ollama(String model, ObjectMapper mapper) {
        return ollama(OLLAMA_URL, model, mapper);
    }

    public static ModelClient ollama(String baseUrl, String model, ObjectMapper mapper) {
        return new OpenAiCompatModelClient(ollamaSettings(baseUrl, model), mapper);
    }

    /** Local server: no key, long timeout (a model may need to load first), few retries. */
    public static LlmSettings ollamaSettings(String baseUrl, String model) {
        return new LlmSettings("ollama", baseUrl, "", model, 8192, 2, 1800, false, false);
    }

    public static final String OLLAMA_URL = "http://localhost:11434/v1";
    public static final String OLLAMA_DEFAULT_MODEL = "llama3.1";

    public static ModelClient anthropic(AnthropicSettings settings, ObjectMapper mapper) {
        return new AnthropicModelClient(settings, mapper);
    }
}

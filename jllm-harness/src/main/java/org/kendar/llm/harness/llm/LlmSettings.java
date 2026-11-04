package org.kendar.llm.harness.llm;

/**
 * Connection settings of an OpenAI-compatible provider.
 *
 * @param baseUrl                including the version prefix, e.g. https://api.openai.com/v1
 * @param requireApiKey          fail fast when the key is blank (false for local servers)
 * @param useMaxCompletionTokens send max_completion_tokens instead of max_tokens (OpenAI proper)
 */
public record LlmSettings(String name, String baseUrl, String apiKey, String model, int maxTokens,
                          int maxRetries, int timeoutSeconds, boolean requireApiKey,
                          boolean useMaxCompletionTokens) {
}

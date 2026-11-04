package org.kendar.llm.harness.llm;

/**
 * Connection settings of the Anthropic Messages API.
 *
 * @param cacheTtl       "1h" for the extended prompt-cache TTL, anything else for the 5 minute default
 * @param thinkingBudget extended-thinking budget in tokens; 0 disables it
 * @param citations      re-send RAG tool results as citable search_result blocks
 */
public record AnthropicSettings(String apiKey, String baseUrl, String model, int maxTokens, int maxRetries,
                                String cacheTtl, int thinkingBudget, int timeoutSeconds, boolean citations) {

    public static AnthropicSettings defaults(String apiKey) {
        return new AnthropicSettings(apiKey, "https://api.anthropic.com", "claude-sonnet-5-5", 8192, 5, "1h", 0, 600,
                true);
    }
}

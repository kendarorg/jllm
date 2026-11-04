package org.kendar.llm.harness.session;

import java.time.Instant;

/**
 * Persisted state of one chat.
 *
 * @param messagesJson JSON lines, one message per line
 * @param toolGroups   comma-separated tool groups enabled for the chat; "*" = all; null = not chosen yet
 */
public record ChatSessionData(String chatId, String agentId, String title, String mode, String messagesJson,
                              String toolGroups, long inputTokens, long outputTokens, long cacheReadTokens,
                              long cacheCreationTokens, Instant createdAt, Instant updatedAt) {

    public static ChatSessionData create(String chatId, String agentId, String title) {
        Instant now = Instant.now();
        return new ChatSessionData(chatId, agentId, title, "DEFAULT", "", null, 0, 0, 0, 0, now, now);
    }

    public ChatSessionData withTitle(String v) {
        return new ChatSessionData(chatId, agentId, v, mode, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt);
    }

    public ChatSessionData withMode(String v) {
        return new ChatSessionData(chatId, agentId, title, v, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt);
    }

    public ChatSessionData withMessagesJson(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, v, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, Instant.now());
    }

    public ChatSessionData withToolGroups(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, v, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt);
    }

    public ChatSessionData withUsageAdded(long in, long out, long cacheRead, long cacheCreation) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens + in,
                outputTokens + out, cacheReadTokens + cacheRead, cacheCreationTokens + cacheCreation, createdAt,
                updatedAt);
    }
}

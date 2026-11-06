package org.kendar.llm.harness.session;

import java.time.Instant;

/**
 * Persisted state of one chat.
 *
 * @param messagesJson JSON lines, one message per line; empty in rows returned by {@link ChatSessionStore#list()}
 *                     of a store that loads the log lazily
 * @param toolGroups   comma-separated tool groups enabled for the chat; "*" = all; null = not chosen yet
 * @param messageCount number of messages in the log (kept in the row, so listing chats does not read the log)
 */
public record ChatSessionData(String chatId, String agentId, String title, String mode, String messagesJson,
                              String toolGroups, long inputTokens, long outputTokens, long cacheReadTokens,
                              long cacheCreationTokens, Instant createdAt, Instant updatedAt, int messageCount,
                              long qualifierInputTokens, long qualifierOutputTokens) {

    /** A row without qualifier usage. */
    public ChatSessionData(String chatId, String agentId, String title, String mode, String messagesJson,
                           String toolGroups, long inputTokens, long outputTokens, long cacheReadTokens,
                           long cacheCreationTokens, Instant createdAt, Instant updatedAt, int messageCount) {
        this(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens, outputTokens, cacheReadTokens,
                cacheCreationTokens, createdAt, updatedAt, messageCount, 0, 0);
    }

    public static ChatSessionData create(String chatId, String agentId, String title) {
        Instant now = Instant.now();
        return new ChatSessionData(chatId, agentId, title, "DEFAULT", "", null, 0, 0, 0, 0, now, now, 0, 0, 0);
    }

    public ChatSessionData withTitle(String v) {
        return new ChatSessionData(chatId, agentId, v, mode, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens);
    }

    public ChatSessionData withMode(String v) {
        return new ChatSessionData(chatId, agentId, title, v, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens);
    }

    public ChatSessionData withMessagesJson(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, v, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, Instant.now(), countLines(v), qualifierInputTokens,
                qualifierOutputTokens);
    }

    public ChatSessionData withToolGroups(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, v, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens);
    }

    public ChatSessionData withUsageAdded(long in, long out, long cacheRead, long cacheCreation) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens + in,
                outputTokens + out, cacheReadTokens + cacheRead, cacheCreationTokens + cacheCreation, createdAt,
                updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens);
    }

    /** Tokens spent by the noise-command qualifier; kept apart from the chat's own usage. */
    public ChatSessionData withQualifierUsageAdded(long in, long out) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens + in,
                qualifierOutputTokens + out);
    }

    /** Same row without the log (for listing). */
    public ChatSessionData withoutMessages() {
        return new ChatSessionData(chatId, agentId, title, mode, "", toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens);
    }

    /** Same row with the log attached (as loaded from disk); the update time is kept. */
    public ChatSessionData withLoadedMessages(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, v, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, countLines(v), qualifierInputTokens, qualifierOutputTokens);
    }

    /** True for a reference line ({@code {"ignore":true,...}}): kept in the log file, never part of the context. */
    static boolean isReference(String line) {
        return line.startsWith("{\"ignore\":true");
    }

    static int countLines(String v) {
        if (v == null || v.isBlank()) return 0;
        int n = 0;
        for (String l : v.split("\n")) if (!l.isBlank() && !isReference(l)) n++;
        return n;
    }
}

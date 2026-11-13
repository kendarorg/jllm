package org.kendar.llm.harness.session;

import java.time.Instant;

/**
 * Persisted state of one chat.
 *
 * @param messagesJson JSON lines, one message per line; empty in rows returned by {@link ChatSessionStore#list()}
 *                     of a store that loads the log lazily
 * @param toolGroups   comma-separated tool groups enabled for the chat; "*" = all; null = not chosen yet
 * @param messageCount number of messages in the log (kept in the row, so listing chats does not read the log)
 * @param principal    id of the user the embedder attached to the chat at creation; null = no principal (single-user).
 *                     Only the id is stored: roles and attributes are resolved again on each use.
 * @param chatTools    JSON of the per-chat tool set (tools, MCP servers, REST APIs, skills offered when the chat
 *                     started, and the ones switched off for this chat only); null = none, the global set applies
 * @param taintedToolUses comma-separated tool_use ids whose results carried untrusted output (taint gating survives a
 *                     restart); null or empty = none
 */
public record ChatSessionData(String chatId, String agentId, String title, String mode, String messagesJson,
                              String toolGroups, long inputTokens, long outputTokens, long cacheReadTokens,
                              long cacheCreationTokens, Instant createdAt, Instant updatedAt, int messageCount,
                              long qualifierInputTokens, long qualifierOutputTokens, String principal,
                              String chatTools, String taintedToolUses) {

    /** The 17 components before {@code taintedToolUses}: no tainted calls. */
    public ChatSessionData(String chatId, String agentId, String title, String mode, String messagesJson,
                           String toolGroups, long inputTokens, long outputTokens, long cacheReadTokens,
                           long cacheCreationTokens, Instant createdAt, Instant updatedAt, int messageCount,
                           long qualifierInputTokens, long qualifierOutputTokens, String principal, String chatTools) {
        this(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens, outputTokens, cacheReadTokens,
                cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens,
                principal, chatTools, null);
    }

    /** The 16 components before {@code chatTools}: no per-chat tool set. */
    public ChatSessionData(String chatId, String agentId, String title, String mode, String messagesJson,
                           String toolGroups, long inputTokens, long outputTokens, long cacheReadTokens,
                           long cacheCreationTokens, Instant createdAt, Instant updatedAt, int messageCount,
                           long qualifierInputTokens, long qualifierOutputTokens, String principal) {
        this(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens, outputTokens, cacheReadTokens,
                cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens,
                principal, null);
    }

    public static ChatSessionData create(String chatId, String agentId, String title) {
        return create(chatId, agentId, title, null);
    }

    public static ChatSessionData create(String chatId, String agentId, String title, String principal) {
        Instant now = Instant.now();
        return new ChatSessionData(chatId, agentId, title, "DEFAULT", "", null, 0, 0, 0, 0, now, now, 0, 0, 0, principal,
                null, null);
    }

    public ChatSessionData withTaintedToolUses(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens,
                qualifierOutputTokens, principal, chatTools, v);
    }

    public ChatSessionData withTitle(String v) {
        return new ChatSessionData(chatId, agentId, v, mode, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens, principal, chatTools, taintedToolUses);
    }

    public ChatSessionData withMode(String v) {
        return new ChatSessionData(chatId, agentId, title, v, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens, principal, chatTools, taintedToolUses);
    }

    public ChatSessionData withMessagesJson(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, v, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, Instant.now(), countLines(v), qualifierInputTokens,
                qualifierOutputTokens, principal, chatTools, taintedToolUses);
    }

    public ChatSessionData withToolGroups(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, v, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens, principal, chatTools, taintedToolUses);
    }

    public ChatSessionData withUsageAdded(long in, long out, long cacheRead, long cacheCreation) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens + in,
                outputTokens + out, cacheReadTokens + cacheRead, cacheCreationTokens + cacheCreation, createdAt,
                updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens, principal, chatTools, taintedToolUses);
    }

    /** Tokens spent by the noise-command qualifier; kept apart from the chat's own usage. */
    public ChatSessionData withQualifierUsageAdded(long in, long out) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens + in,
                qualifierOutputTokens + out, principal, chatTools, taintedToolUses);
    }

    public ChatSessionData withChatTools(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, messagesJson, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens,
                qualifierOutputTokens, principal, v, taintedToolUses);
    }

    /** Same row without the log (for listing). */
    public ChatSessionData withoutMessages() {
        return new ChatSessionData(chatId, agentId, title, mode, "", toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, messageCount, qualifierInputTokens, qualifierOutputTokens, principal, chatTools, taintedToolUses);
    }

    /** Same row with the log attached (as loaded from disk); the update time is kept. */
    public ChatSessionData withLoadedMessages(String v) {
        return new ChatSessionData(chatId, agentId, title, mode, v, toolGroups, inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens, createdAt, updatedAt, countLines(v), qualifierInputTokens, qualifierOutputTokens, principal, chatTools, taintedToolUses);
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

package org.kendar.llm.harness.session;

import java.time.Instant;

/**
 * One sub-agent investigation; kept out of the chat log so it never reaches the main agent's context.
 *
 * @param toolUseId    the tool_use id of the parent "task" call; null when unknown
 * @param messagesJson JSON lines, same format as {@link ChatSessionData#messagesJson()}
 * @param status       "ok" or "failed"
 */
public record SubAgentRunData(String id, String chatId, String toolUseId, String prompt, String messagesJson,
                              String answer, String status, long inputTokens, long outputTokens,
                              long cacheReadTokens, long cacheCreationTokens, Instant createdAt) {
}

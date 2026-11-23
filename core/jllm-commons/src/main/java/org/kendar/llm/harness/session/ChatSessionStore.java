package org.kendar.llm.harness.session;

import java.util.List;
import java.util.Optional;

/** Persistence port for chats. Rows are scoped strictly by chatId. */
public interface ChatSessionStore {

    /** Inserts or replaces the chat with the same id. */
    ChatSessionData save(ChatSessionData session);

    Optional<ChatSessionData> find(String chatId);

    /** The chats {@code visibility} admits, most recently updated first; the filter runs in the store. */
    List<ChatSessionData> list(ChatVisibility visibility);

    void delete(String chatId);
}

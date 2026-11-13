package org.kendar.llm.harness.session;

import java.util.List;
import java.util.Optional;

/** Persistence port for chats. Rows are scoped strictly by chatId. */
public interface ChatSessionStore {

    /** Inserts or replaces the chat with the same id. */
    ChatSessionData save(ChatSessionData session);

    Optional<ChatSessionData> find(String chatId);

    /** Most recently updated first. */
    List<ChatSessionData> list();

    void delete(String chatId);
}

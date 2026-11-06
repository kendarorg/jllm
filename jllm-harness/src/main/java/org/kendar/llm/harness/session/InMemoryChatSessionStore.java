package org.kendar.llm.harness.session;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryChatSessionStore implements ChatSessionStore {

    private final Map<String, ChatSessionData> store = new ConcurrentHashMap<>();

    @Override
    public ChatSessionData save(ChatSessionData session) {
        store.put(session.chatId(), session);
        return session;
    }

    @Override
    public Optional<ChatSessionData> find(String chatId) {
        return Optional.ofNullable(store.get(chatId));
    }

    @Override
    public List<ChatSessionData> list() {
        List<ChatSessionData> out = new ArrayList<>(store.values());
        out.sort(Comparator.comparing(ChatSessionData::updatedAt).reversed());
        return out;
    }

    @Override
    public void delete(String chatId) {
        store.remove(chatId);
    }
}

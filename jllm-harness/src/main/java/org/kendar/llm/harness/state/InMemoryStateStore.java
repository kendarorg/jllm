package org.kendar.llm.harness.state;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStateStore implements StateStore {

    private final Map<String, Map<String, JsonNode>> data = new ConcurrentHashMap<>();

    @Override
    public JsonNode load(String chatId, String name) {
        Map<String, JsonNode> m = data.get(chatId);
        return m == null ? null : m.get(name);
    }

    @Override
    public void save(String chatId, String name, JsonNode value) {
        data.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>()).put(name, value);
    }

    @Override
    public void delete(String chatId) {
        data.remove(chatId);
    }
}

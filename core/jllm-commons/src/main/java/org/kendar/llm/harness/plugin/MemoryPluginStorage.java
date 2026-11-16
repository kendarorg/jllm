package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link PluginStorage}, for tests. */
public final class MemoryPluginStorage implements PluginStorage {
    private static final char SEP = '|';
    private final ConcurrentHashMap<String, TreeMap<String, JsonNode>> data = new ConcurrentHashMap<>();

    private static String k(String owner, String c) {
        return owner + SEP + c;
    }

    @Override
    public Optional<JsonNode> get(String owner, String collection, String id) {
        var m = data.get(k(owner, collection));
        if (m == null) return Optional.empty();
        synchronized (m) {
            return Optional.ofNullable(m.get(id));
        }
    }

    @Override
    public void put(String owner, String collection, String id, JsonNode doc) {
        var m = data.computeIfAbsent(k(owner, collection), x -> new TreeMap<>());
        synchronized (m) {
            m.put(id, doc.deepCopy());
        }
    }

    @Override
    public void delete(String owner, String collection, String id) {
        var m = data.get(k(owner, collection));
        if (m != null) synchronized (m) {
            m.remove(id);
        }
    }

    @Override
    public List<String> ids(String owner, String collection) {
        var m = data.get(k(owner, collection));
        if (m == null) return List.of();
        synchronized (m) {
            return List.copyOf(m.keySet());
        }
    }

    @Override
    public List<String> owners(String collection) {
        return data.entrySet().stream().filter(e -> e.getKey().endsWith(SEP + collection) && !e.getValue().isEmpty())
                .map(e -> e.getKey().substring(0, e.getKey().length() - collection.length() - 1)).sorted().toList();
    }
}

package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Simple document storage kept apart by plugin id, with one area per user and a shared {@link #GLOBAL} area. The host
 * backs it with its configured storage.
 */
public interface PluginStorage {
    String GLOBAL = "_global";

    Optional<JsonNode> get(String owner, String collection, String id);

    void put(String owner, String collection, String id, JsonNode doc);

    void delete(String owner, String collection, String id);

    /** Ids of the collection, sorted. */
    List<String> ids(String owner, String collection);

    /** Owners that have documents in the collection (users and possibly {@link #GLOBAL}). */
    List<String> owners(String collection);
}

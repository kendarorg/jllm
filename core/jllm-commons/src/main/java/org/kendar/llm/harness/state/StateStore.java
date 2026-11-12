package org.kendar.llm.harness.state;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small per-chat JSON documents that must survive a restart (tool grants, todo lists). Each document is addressed by
 * chat and name; the default is in memory, {@link FileStateStore} keeps them next to the chat history.
 */
public interface StateStore {

    /** The document, or null when none was saved. */
    JsonNode load(String chatId, String name);

    void save(String chatId, String name, JsonNode value);

    /** Drops every document of the chat. */
    void delete(String chatId);
}

package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/** A per-user channel a plugin pushes events into; the host forwards them to the user's open event stream. */
public interface PluginEvents {
    record Event(String type, JsonNode data) {
    }

    void push(String user, String type, JsonNode data);

    /** @return a handle that unsubscribes */
    Runnable subscribe(String user, Consumer<Event> listener);
}

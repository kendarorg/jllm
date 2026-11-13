package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** An MCP connection: initialize, tools/list and tools/call. Closing it releases the transport. */
public interface McpClient extends Closeable {

    void initialize() throws IOException, InterruptedException;

    /** @return the entries of tools/list, following pagination */
    List<JsonNode> listTools() throws IOException, InterruptedException;

    /** @return the result object of tools/call */
    JsonNode callTool(String name, JsonNode arguments) throws IOException, InterruptedException;

    /**
     * A call made for one principal: its headers are added to the request and, where the transport keeps sessions,
     * the call uses that principal's session. The default ignores both (stdio servers: one process, no headers).
     *
     * @param principalId null when the chat has no principal
     */
    default JsonNode callTool(String name, JsonNode arguments, String principalId, java.util.Map<String, String> headers)
            throws IOException, InterruptedException {
        return callTool(name, arguments);
    }

    /** Ends the session of one principal (logout, idle); the next call of that principal opens a new one. */
    default void closeSession(String principalId) {
    }

    @Override
    default void close() throws IOException {
    }
}

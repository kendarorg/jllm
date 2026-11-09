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

    @Override
    default void close() throws IOException {
    }
}

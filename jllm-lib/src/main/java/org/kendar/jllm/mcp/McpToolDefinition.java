package org.kendar.jllm.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** An MCP tool as advertised by a server's {@code tools/list} response. */
public class McpToolDefinition {

  private final String name;
  private final String description;
  private final ObjectNode inputSchema;

  public McpToolDefinition(String name, String description, ObjectNode inputSchema) {
    this.name = name;
    this.description = description;
    this.inputSchema = inputSchema;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public ObjectNode getInputSchema() {
    return inputSchema;
  }
}

package org.kendar.jllm.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMMcpException;
import org.kendar.jllm.exceptions.LLMToolException;

import java.util.Map;

/**
 * Adapts a single MCP server tool to the {@link LLMTool} contract so it can be
 * registered alongside the native tools. The exposed name is namespaced
 * ({@code mcp__<server>__<tool>}) to avoid collisions, while calls are forwarded
 * to the server under the tool's original name.
 */
public class McpTool implements LLMTool {

  private final McpClient client;
  private final McpToolDefinition definition;
  private final String exposedName;

  public McpTool(McpClient client, McpToolDefinition definition) {
    this.client = client;
    this.definition = definition;
    this.exposedName = ("mcp__" + client.name() + "__" + definition.getName())
        .toLowerCase().replaceAll("[^a-z0-9_]", "_");
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    return true; // external tools are not sandboxed; gate them by default
  }

  @Override
  public String name() {
    return exposedName;
  }

  @Override
  public String toolSchema() {
    ObjectNode root = LLMObjectMapper.getObjectMapper().createObjectNode();
    root.put("type", "function");
    ObjectNode function = root.putObject("function");
    function.put("name", exposedName);
    String desc = definition.getDescription();
    function.put("description",
        "[MCP:" + client.name() + "] " + (desc == null || desc.isBlank() ? definition.getName() : desc));
    ObjectNode params = definition.getInputSchema();
    if (params != null && params.size() > 0) {
      function.set("parameters", params);
    } else {
      function.putObject("parameters").put("type", "object");
    }
    try {
      return LLMObjectMapper.getObjectMapper().writeValueAsString(root);
    } catch (Exception e) {
      throw new LLMToolException("Unable to serialize MCP tool schema for " + exposedName, e);
    }
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    try {
      return client.callTool(definition.getName(), args);
    } catch (LLMMcpException e) {
      throw new LLMToolException("MCP tool '" + exposedName + "' failed: " + e.getMessage(), e);
    }
  }
}

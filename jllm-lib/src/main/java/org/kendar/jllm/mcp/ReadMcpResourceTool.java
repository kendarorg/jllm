package org.kendar.jllm.mcp;

import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMMcpException;
import org.kendar.jllm.exceptions.LLMToolException;

import java.util.Map;

/**
 * Reads a resource exposed by a connected MCP server. The {@code server} argument
 * may be omitted when only one server provides resources.
 */
public class ReadMcpResourceTool implements LLMTool {

  private final McpManager manager;

  public ReadMcpResourceTool(McpManager manager) {
    this.manager = manager;
  }

  @Override
  public boolean available() {
    return manager.hasResources();
  }

  @Override
  public boolean requiresApproval() {
    return true;
  }

  @Override
  public String name() {
    return "read_mcp_resource";
  }

  @Override
  public String toolSchema() {
    return org.kendar.jllm.tools.ToolSchemas.builder(name(),
            "Read the contents of a resource exposed by a connected MCP server. "
                + "See the 'MCP resources' section of the system prompt for available URIs.")
        .prop("uri", "string", "The resource URI to read.", true)
        .prop("server", "string",
            "The MCP server name (optional when only one server provides resources).", false)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String uri = args == null ? null : args.get("uri");
    if (uri == null || uri.isBlank()) {
      throw new LLMToolException("Missing required argument: uri");
    }
    String server = args.get("server");
    McpClient client = manager.resolveResourceClient(server, uri);
    if (client == null) {
      throw new LLMToolException("No MCP server found for resource '" + uri + "'"
          + (server == null ? "" : " on server '" + server + "'"));
    }
    try {
      return client.readResource(uri);
    } catch (LLMMcpException e) {
      throw new LLMToolException("Unable to read MCP resource '" + uri + "': " + e.getMessage(), e);
    }
  }
}

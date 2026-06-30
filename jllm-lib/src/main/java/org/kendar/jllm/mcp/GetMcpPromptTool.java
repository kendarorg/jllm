package org.kendar.jllm.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMMcpException;
import org.kendar.jllm.exceptions.LLMToolException;

import java.util.Map;

/**
 * Expands a prompt template exposed by a connected MCP server and returns its
 * rendered message transcript, which the model can then act on. The {@code server}
 * argument may be omitted when only one server provides the named prompt.
 */
public class GetMcpPromptTool implements LLMTool {

  private final McpManager manager;

  public GetMcpPromptTool(McpManager manager) {
    this.manager = manager;
  }

  @Override
  public boolean available() {
    return manager.hasPrompts();
  }

  @Override
  public boolean requiresApproval() {
    return false;
  }

  @Override
  public String name() {
    return "get_mcp_prompt";
  }

  @Override
  public String toolSchema() {
    return org.kendar.jllm.tools.ToolSchemas.builder(name(),
            "Fetch and expand a prompt template exposed by a connected MCP server. "
                + "See the 'MCP prompts' section of the system prompt for available prompts.")
        .prop("name", "string", "The prompt name.", true)
        .prop("server", "string",
            "The MCP server name (optional when only one server provides the prompt).", false)
        .prop("args", "string",
            "A JSON object of prompt arguments, e.g. {\"language\":\"java\"}. Optional.", false)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String promptName = args == null ? null : args.get("name");
    if (promptName == null || promptName.isBlank()) {
      throw new LLMToolException("Missing required argument: name");
    }
    String server = args.get("server");
    McpClient client = manager.resolvePromptClient(server, promptName);
    if (client == null) {
      throw new LLMToolException("No MCP server found for prompt '" + promptName + "'"
          + (server == null ? "" : " on server '" + server + "'"));
    }

    Map<String, String> promptArgs = Map.of();
    String rawArgs = args.get("args");
    if (rawArgs != null && !rawArgs.isBlank()) {
      try {
        promptArgs = LLMObjectMapper.getObjectMapper().readValue(
            rawArgs, new TypeReference<Map<String, String>>() {
            });
      } catch (Exception e) {
        throw new LLMToolException("'args' must be a JSON object of string values: " + e.getMessage(), e);
      }
    }
    try {
      return client.getPrompt(promptName, promptArgs);
    } catch (LLMMcpException e) {
      throw new LLMToolException("Unable to get MCP prompt '" + promptName + "': " + e.getMessage(), e);
    }
  }
}

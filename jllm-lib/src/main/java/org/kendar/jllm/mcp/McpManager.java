package org.kendar.jllm.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.tools.LLMToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Discovers MCP servers from {@code .jllm/mcp.json}, starts each one, and
 * registers their tools into the shared {@link LLMToolRegistry}. A single
 * manager owns the spawned client processes and must be {@link #close() closed}
 * when the session ends. Failures starting an individual server are isolated:
 * the server is skipped and the rest continue.
 */
public class McpManager implements AutoCloseable {

  private final List<McpClient> clients = new ArrayList<>();

  /**
   * Loads {@code <workingDir>/.jllm/mcp.json} (if present), starts the configured
   * servers and registers their tools. Always returns a manager (possibly empty).
   */
  public static McpManager loadAndRegister(Path workingDir, LLMToolRegistry registry) {
    McpManager manager = new McpManager();
    Path configFile = workingDir.resolve(".jllm").resolve("mcp.json");
    if (!Files.isRegularFile(configFile)) {
      return manager;
    }
    List<McpServerConfig> servers;
    try {
      servers = parse(Files.readString(configFile));
    } catch (IOException e) {
      System.err.println("[mcp] Unable to read " + configFile + ": " + e.getMessage());
      return manager;
    }
    for (McpServerConfig server : servers) {
      if (!server.isEnabled()) {
        continue;
      }
      manager.startServer(server, registry);
    }
    return manager;
  }

  private void startServer(McpServerConfig server, LLMToolRegistry registry) {
    McpClient client = new McpClient(server);
    try {
      client.start();
      List<McpToolDefinition> tools = client.listTools();
      for (McpToolDefinition def : tools) {
        registry.register(new McpTool(client, def));
      }
      clients.add(client);
      System.err.println("[mcp] Registered " + tools.size() + " tool(s) from server '"
          + server.getName() + "'");
    } catch (RuntimeException e) {
      System.err.println("[mcp] Skipping server '" + server.getName() + "': " + e.getMessage());
      client.close();
    }
  }

  static List<McpServerConfig> parse(String json) {
    List<McpServerConfig> result = new ArrayList<>();
    try {
      JsonNode root = LLMObjectMapper.getObjectMapper().readTree(json);
      JsonNode servers = root.get("mcpServers");
      if (servers == null || !servers.isObject()) {
        return result;
      }
      Iterator<Map.Entry<String, JsonNode>> fields = servers.properties().iterator();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        JsonNode node = entry.getValue();
        McpServerConfig cfg = new McpServerConfig();
        cfg.setName(entry.getKey());
        cfg.setCommand(node.path("command").asText(null));
        JsonNode argsNode = node.get("args");
        if (argsNode != null && argsNode.isArray()) {
          List<String> args = new ArrayList<>();
          argsNode.forEach(a -> args.add(a.asText()));
          cfg.setArgs(args);
        }
        JsonNode envNode = node.get("env");
        if (envNode != null && envNode.isObject()) {
          Iterator<Map.Entry<String, JsonNode>> envFields = envNode.properties().iterator();
          while (envFields.hasNext()) {
            Map.Entry<String, JsonNode> e = envFields.next();
            cfg.getEnv().put(e.getKey(), e.getValue().asText());
          }
        }
        if (node.has("enabled")) {
          cfg.setEnabled(node.get("enabled").asBoolean(true));
        }
        result.add(cfg);
      }
    } catch (IOException e) {
      System.err.println("[mcp] Unable to parse mcp.json: " + e.getMessage());
    }
    return result;
  }

  public int serverCount() {
    return clients.size();
  }

  @Override
  public void close() {
    for (McpClient client : clients) {
      client.close();
    }
    clients.clear();
  }
}

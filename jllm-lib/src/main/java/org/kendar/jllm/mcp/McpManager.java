package org.kendar.jllm.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.tools.LLMToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers MCP servers from {@code .jllm/mcp.json}, starts each one, and
 * registers their tools, resources and prompts. Tools are registered directly
 * into the shared {@link LLMToolRegistry}; resources and prompts are exposed
 * through the {@code read_mcp_resource} and {@code get_mcp_prompt} tools plus
 * catalogs surfaced in the system prompt. A single manager owns the spawned
 * client processes and must be {@link #close() closed} when the session ends.
 */
public class McpManager implements AutoCloseable {

  private final Map<String, McpClient> clientsByName = new LinkedHashMap<>();
  private final Map<String, List<McpResourceDefinition>> resourcesByServer = new LinkedHashMap<>();
  private final Map<String, List<McpPromptDefinition>> promptsByServer = new LinkedHashMap<>();

  /**
   * Loads {@code <workingDir>/.jllm/mcp.json} (if present), starts the configured
   * servers and registers their tools, resources and prompts. Always returns a
   * manager (possibly empty).
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
    // Resource/prompt access goes through aggregate tools, registered once.
    if (manager.hasResources()) {
      registry.register(new ReadMcpResourceTool(manager));
    }
    if (manager.hasPrompts()) {
      registry.register(new GetMcpPromptTool(manager));
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
      clientsByName.put(server.getName(), client);

      int resourceCount = 0;
      if (client.supports("resources")) {
        List<McpResourceDefinition> resources = client.listResources();
        resourcesByServer.put(server.getName(), resources);
        resourceCount = resources.size();
      }
      int promptCount = 0;
      if (client.supports("prompts")) {
        List<McpPromptDefinition> prompts = client.listPrompts();
        promptsByServer.put(server.getName(), prompts);
        promptCount = prompts.size();
      }
      System.err.println("[mcp] Server '" + server.getName() + "': " + tools.size()
          + " tool(s), " + resourceCount + " resource(s), " + promptCount + " prompt(s)");
    } catch (RuntimeException e) {
      System.err.println("[mcp] Skipping server '" + server.getName() + "': " + e.getMessage());
      client.close();
    }
  }

  public boolean hasResources() {
    return resourcesByServer.values().stream().anyMatch(l -> !l.isEmpty());
  }

  public boolean hasPrompts() {
    return promptsByServer.values().stream().anyMatch(l -> !l.isEmpty());
  }

  /** Resolves which client to read a resource from, by explicit server or by URI match. */
  public McpClient resolveResourceClient(String server, String uri) {
    if (server != null && !server.isBlank()) {
      return clientsByName.get(server);
    }
    String onlyServer = null;
    int serversWithResources = 0;
    for (Map.Entry<String, List<McpResourceDefinition>> e : resourcesByServer.entrySet()) {
      if (e.getValue().isEmpty()) {
        continue;
      }
      serversWithResources++;
      onlyServer = e.getKey();
      for (McpResourceDefinition r : e.getValue()) {
        if (r.getUri().equals(uri)) {
          return clientsByName.get(e.getKey());
        }
      }
    }
    // No exact URI match: fall back to the sole resource-providing server, if unambiguous.
    return serversWithResources == 1 ? clientsByName.get(onlyServer) : null;
  }

  /** Resolves which client to fetch a prompt from, by explicit server or by prompt name. */
  public McpClient resolvePromptClient(String server, String promptName) {
    if (server != null && !server.isBlank()) {
      return clientsByName.get(server);
    }
    for (Map.Entry<String, List<McpPromptDefinition>> e : promptsByServer.entrySet()) {
      for (McpPromptDefinition p : e.getValue()) {
        if (p.getName().equals(promptName)) {
          return clientsByName.get(e.getKey());
        }
      }
    }
    return null;
  }

  /** Human-readable list of resources for the system prompt; empty when none. */
  public String resourcesCatalog() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<McpResourceDefinition>> e : resourcesByServer.entrySet()) {
      for (McpResourceDefinition r : e.getValue()) {
        sb.append("- ").append(e.getKey()).append(": ").append(r.getUri());
        if (r.getName() != null && !r.getName().isBlank()) {
          sb.append(" (").append(r.getName()).append(')');
        }
        if (r.getDescription() != null && !r.getDescription().isBlank()) {
          sb.append(" — ").append(r.getDescription().trim().replaceAll("\\s+", " "));
        }
        sb.append('\n');
      }
    }
    return sb.toString().trim();
  }

  /** Human-readable list of prompts for the system prompt; empty when none. */
  public String promptsCatalog() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<McpPromptDefinition>> e : promptsByServer.entrySet()) {
      for (McpPromptDefinition p : e.getValue()) {
        sb.append("- ").append(e.getKey()).append('/').append(p.getName());
        if (p.getArguments() != null && !p.getArguments().isEmpty()) {
          List<String> names = new ArrayList<>();
          for (McpPromptDefinition.Argument a : p.getArguments()) {
            names.add(a.isRequired() ? a.getName() + "*" : a.getName());
          }
          sb.append('(').append(String.join(", ", names)).append(')');
        }
        if (p.getDescription() != null && !p.getDescription().isBlank()) {
          sb.append(" — ").append(p.getDescription().trim().replaceAll("\\s+", " "));
        }
        sb.append('\n');
      }
    }
    return sb.toString().trim();
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
    return clientsByName.size();
  }

  @Override
  public void close() {
    for (McpClient client : clientsByName.values()) {
      client.close();
    }
    clientsByName.clear();
    resourcesByServer.clear();
    promptsByServer.clear();
  }
}

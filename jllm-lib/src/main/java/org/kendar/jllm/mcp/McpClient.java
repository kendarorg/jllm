package org.kendar.jllm.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.exceptions.LLMMcpException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal MCP client speaking newline-delimited JSON-RPC 2.0 over a child
 * process's stdio (the MCP stdio transport). Supports the subset needed to expose
 * a server's tools to the agent: {@code initialize}, {@code tools/list} and
 * {@code tools/call}. A background thread reads responses and completes the
 * futures keyed by request id.
 */
public class McpClient implements AutoCloseable {

  private static final String PROTOCOL_VERSION = "2024-11-05";
  private static final long REQUEST_TIMEOUT_SECONDS = 60;

  private final McpServerConfig config;
  private final ObjectMapper mapper = LLMObjectMapper.getObjectMapper();
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

  private Process process;
  private OutputStream stdin;
  private Thread readerThread;
  private volatile boolean closed;
  private JsonNode capabilities;

  public McpClient(McpServerConfig config) {
    this.config = config;
  }

  public String name() {
    return config.getName();
  }

  /** Starts the server process and performs the MCP initialize handshake. */
  public synchronized void start() {
    if (config.getCommand() == null || config.getCommand().isBlank()) {
      throw new LLMMcpException("MCP server '" + config.getName() + "' has no command");
    }
    List<String> command = new ArrayList<>();
    command.add(config.getCommand());
    command.addAll(config.getArgs());

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().putAll(config.getEnv());
    // Keep stderr separate so server diagnostics don't corrupt the JSON-RPC stream.
    try {
      process = pb.start();
    } catch (IOException e) {
      throw new LLMMcpException("Unable to start MCP server '" + config.getName() + "'", e);
    }
    stdin = process.getOutputStream();
    startReader();

    ObjectNode params = mapper.createObjectNode();
    params.put("protocolVersion", PROTOCOL_VERSION);
    params.putObject("capabilities");
    ObjectNode clientInfo = params.putObject("clientInfo");
    clientInfo.put("name", "jllm");
    clientInfo.put("version", "0.1");
    JsonNode init = request("initialize", params);
    if (init != null) {
      this.capabilities = init.get("capabilities");
    }

    notification("notifications/initialized", mapper.createObjectNode());
  }

  /** Whether the server declared the given capability (e.g. "resources", "prompts", "tools"). */
  public boolean supports(String capability) {
    return capabilities != null && capabilities.has(capability);
  }

  private void startReader() {
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    readerThread = new Thread(() -> {
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          dispatch(line);
        }
      } catch (IOException e) {
        if (!closed) {
          failAllPending(new LLMMcpException("MCP stream closed: " + e.getMessage(), e));
        }
      }
    }, "mcp-reader-" + config.getName());
    readerThread.setDaemon(true);
    readerThread.start();
  }

  private void dispatch(String line) {
    JsonNode node;
    try {
      node = mapper.readTree(line);
    } catch (IOException e) {
      return; // ignore non-JSON noise on the line
    }
    JsonNode idNode = node.get("id");
    if (idNode == null || idNode.isNull()) {
      return; // a notification from the server - nothing to await
    }
    CompletableFuture<JsonNode> future = pending.remove(idNode.asInt());
    if (future == null) {
      return;
    }
    JsonNode error = node.get("error");
    if (error != null && !error.isNull()) {
      future.completeExceptionally(new LLMMcpException(
          "MCP error from '" + config.getName() + "': " + error.toString()));
    } else {
      future.complete(node.get("result"));
    }
  }

  private JsonNode request(String method, ObjectNode params) {
    int id = nextId.getAndIncrement();
    ObjectNode message = mapper.createObjectNode();
    message.put("jsonrpc", "2.0");
    message.put("id", id);
    message.put("method", method);
    message.set("params", params);

    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);
    writeLine(message);
    try {
      return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      pending.remove(id);
      throw new LLMMcpException("MCP request '" + method + "' timed out on server '"
          + config.getName() + "'", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof LLMMcpException mcp) {
        throw mcp;
      }
      throw new LLMMcpException("MCP request '" + method + "' failed", cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LLMMcpException("Interrupted waiting for MCP request '" + method + "'", e);
    }
  }

  private void notification(String method, ObjectNode params) {
    ObjectNode message = mapper.createObjectNode();
    message.put("jsonrpc", "2.0");
    message.put("method", method);
    message.set("params", params);
    writeLine(message);
  }

  private synchronized void writeLine(ObjectNode message) {
    try {
      stdin.write(mapper.writeValueAsBytes(message));
      stdin.write('\n');
      stdin.flush();
    } catch (IOException e) {
      throw new LLMMcpException("Unable to write to MCP server '" + config.getName() + "'", e);
    }
  }

  /** Returns the tool definitions advertised by the server. */
  public List<McpToolDefinition> listTools() {
    JsonNode result = request("tools/list", mapper.createObjectNode());
    List<McpToolDefinition> tools = new ArrayList<>();
    if (result == null) {
      return tools;
    }
    JsonNode arr = result.get("tools");
    if (arr != null && arr.isArray()) {
      for (JsonNode t : arr) {
        String name = t.path("name").asText(null);
        if (name == null) {
          continue;
        }
        String description = t.path("description").asText("");
        JsonNode inputSchema = t.get("inputSchema");
        ObjectNode schema = (inputSchema != null && inputSchema.isObject())
            ? (ObjectNode) inputSchema
            : mapper.createObjectNode().put("type", "object");
        tools.add(new McpToolDefinition(name, description, schema));
      }
    }
    return tools;
  }

  /** Invokes a tool and returns its textual content. */
  public String callTool(String toolName, Map<String, String> args) {
    ObjectNode params = mapper.createObjectNode();
    params.put("name", toolName);
    ObjectNode arguments = params.putObject("arguments");
    if (args != null) {
      for (Map.Entry<String, String> e : args.entrySet()) {
        arguments.put(e.getKey(), e.getValue());
      }
    }
    JsonNode result = request("tools/call", params);
    return extractText(result);
  }

  private String extractText(JsonNode result) {
    if (result == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    JsonNode content = result.get("content");
    if (content != null && content.isArray()) {
      for (JsonNode block : content) {
        if ("text".equals(block.path("type").asText())) {
          if (sb.length() > 0) {
            sb.append('\n');
          }
          sb.append(block.path("text").asText(""));
        }
      }
    }
    if (result.path("isError").asBoolean(false)) {
      return "Error: " + sb;
    }
    return sb.length() == 0 ? result.toString() : sb.toString();
  }

  /** Returns the resources advertised by the server. */
  public List<McpResourceDefinition> listResources() {
    JsonNode result = request("resources/list", mapper.createObjectNode());
    List<McpResourceDefinition> resources = new ArrayList<>();
    if (result == null) {
      return resources;
    }
    JsonNode arr = result.get("resources");
    if (arr != null && arr.isArray()) {
      for (JsonNode r : arr) {
        String uri = r.path("uri").asText(null);
        if (uri == null) {
          continue;
        }
        resources.add(new McpResourceDefinition(
            uri,
            r.path("name").asText(""),
            r.path("description").asText(""),
            r.path("mimeType").asText("")));
      }
    }
    return resources;
  }

  /** Reads a resource by URI and returns its concatenated textual contents. */
  public String readResource(String uri) {
    ObjectNode params = mapper.createObjectNode();
    params.put("uri", uri);
    JsonNode result = request("resources/read", params);
    if (result == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    JsonNode contents = result.get("contents");
    if (contents != null && contents.isArray()) {
      for (JsonNode c : contents) {
        if (c.hasNonNull("text")) {
          if (sb.length() > 0) {
            sb.append('\n');
          }
          sb.append(c.path("text").asText(""));
        } else if (c.hasNonNull("blob")) {
          if (sb.length() > 0) {
            sb.append('\n');
          }
          sb.append("[binary resource ").append(c.path("mimeType").asText("application/octet-stream"))
              .append(", base64 omitted]");
        }
      }
    }
    return sb.length() == 0 ? result.toString() : sb.toString();
  }

  /** Returns the prompt templates advertised by the server. */
  public List<McpPromptDefinition> listPrompts() {
    JsonNode result = request("prompts/list", mapper.createObjectNode());
    List<McpPromptDefinition> prompts = new ArrayList<>();
    if (result == null) {
      return prompts;
    }
    JsonNode arr = result.get("prompts");
    if (arr != null && arr.isArray()) {
      for (JsonNode p : arr) {
        String name = p.path("name").asText(null);
        if (name == null) {
          continue;
        }
        List<McpPromptDefinition.Argument> args = new ArrayList<>();
        JsonNode argsNode = p.get("arguments");
        if (argsNode != null && argsNode.isArray()) {
          for (JsonNode a : argsNode) {
            args.add(new McpPromptDefinition.Argument(
                a.path("name").asText(""),
                a.path("description").asText(""),
                a.path("required").asBoolean(false)));
          }
        }
        prompts.add(new McpPromptDefinition(name, p.path("description").asText(""), args));
      }
    }
    return prompts;
  }

  /** Expands a prompt template with the given arguments into a flattened message transcript. */
  public String getPrompt(String name, Map<String, String> arguments) {
    ObjectNode params = mapper.createObjectNode();
    params.put("name", name);
    ObjectNode args = params.putObject("arguments");
    if (arguments != null) {
      for (Map.Entry<String, String> e : arguments.entrySet()) {
        args.put(e.getKey(), e.getValue());
      }
    }
    JsonNode result = request("prompts/get", params);
    if (result == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    String description = result.path("description").asText("");
    if (!description.isBlank()) {
      sb.append(description).append("\n\n");
    }
    JsonNode messages = result.get("messages");
    if (messages != null && messages.isArray()) {
      for (JsonNode m : messages) {
        String role = m.path("role").asText("user");
        JsonNode content = m.get("content");
        String text;
        if (content != null && content.isObject()) {
          text = content.path("text").asText("");
        } else {
          text = content == null ? "" : content.asText("");
        }
        sb.append(role).append(": ").append(text).append("\n\n");
      }
    }
    return sb.toString().trim();
  }

  private void failAllPending(LLMMcpException ex) {
    for (CompletableFuture<JsonNode> f : pending.values()) {
      f.completeExceptionally(ex);
    }
    pending.clear();
  }

  @Override
  public synchronized void close() {
    closed = true;
    failAllPending(new LLMMcpException("MCP client closed"));
    if (process != null) {
      process.destroy();
      try {
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }
}

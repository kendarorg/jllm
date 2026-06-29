package org.kendar.jllm.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a single MCP (Model Context Protocol) server launched over
 * stdio. Mirrors the widely-used {@code mcpServers} JSON shape:
 * <pre>
 * { "mcpServers": { "files": { "command": "npx", "args": ["-y", "server"], "env": {} } } }
 * </pre>
 */
public class McpServerConfig {

  private String name;
  private String command;
  private List<String> args = new ArrayList<>();
  private Map<String, String> env = new LinkedHashMap<>();
  private boolean enabled = true;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCommand() {
    return command;
  }

  public void setCommand(String command) {
    this.command = command;
  }

  public List<String> getArgs() {
    return args;
  }

  public void setArgs(List<String> args) {
    this.args = args == null ? new ArrayList<>() : args;
  }

  public Map<String, String> getEnv() {
    return env;
  }

  public void setEnv(Map<String, String> env) {
    this.env = env == null ? new LinkedHashMap<>() : env;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}

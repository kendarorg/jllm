package org.kendar.jllm.mcp;

import java.util.List;

/** A prompt template advertised by an MCP server's {@code prompts/list} response. */
public class McpPromptDefinition {

  /** A single prompt argument descriptor. */
  public static final class Argument {
    private final String name;
    private final String description;
    private final boolean required;

    public Argument(String name, String description, boolean required) {
      this.name = name;
      this.description = description;
      this.required = required;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public boolean isRequired() {
      return required;
    }
  }

  private final String name;
  private final String description;
  private final List<Argument> arguments;

  public McpPromptDefinition(String name, String description, List<Argument> arguments) {
    this.name = name;
    this.description = description;
    this.arguments = arguments;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public List<Argument> getArguments() {
    return arguments;
  }
}

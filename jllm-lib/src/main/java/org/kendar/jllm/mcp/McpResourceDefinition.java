package org.kendar.jllm.mcp;

/** A resource advertised by an MCP server's {@code resources/list} response. */
public class McpResourceDefinition {

  private final String uri;
  private final String name;
  private final String description;
  private final String mimeType;

  public McpResourceDefinition(String uri, String name, String description, String mimeType) {
    this.uri = uri;
    this.name = name;
    this.description = description;
    this.mimeType = mimeType;
  }

  public String getUri() {
    return uri;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getMimeType() {
    return mimeType;
  }
}

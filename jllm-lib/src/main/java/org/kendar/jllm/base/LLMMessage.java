package org.kendar.jllm.base;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single chat message exchanged with the LLM.
 * Roles are one of: system, user, assistant, tool.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LLMMessage {
  private String role;
  private String content;
  private String toolCallId;
  private String toolName;

  public LLMMessage() {
  }

  public LLMMessage(String role, String content) {
    this.role = role;
    this.content = content;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  public void setToolCallId(String toolCallId) {
    this.toolCallId = toolCallId;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }
}

package org.kendar.jllm.base;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed tool call requested by the model in an Ollama /api/chat response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LLMToolCall {
  private String id;
  private String name;
  private Map<String, Object> arguments = new LinkedHashMap<>();

  public LLMToolCall() {
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Map<String, Object> getArguments() {
    return arguments;
  }

  public void setArguments(Map<String, Object> arguments) {
    this.arguments = arguments;
  }
}

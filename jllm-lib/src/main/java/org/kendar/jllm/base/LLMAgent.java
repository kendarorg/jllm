package org.kendar.jllm.base;

import java.util.List;

public interface LLMAgent {
  String getName();
  String getDescription();
  String getOutputFormat();

  /** Persona/system prompt for the agent; defaults to its description. */
  default String getSystemPrompt() {
    return getDescription();
  }

  /** Optional model override; null means use the client default. */
  default String getModel() {
    return null;
  }

  /** Tools the agent may use; empty means no restriction. */
  default List<String> getAllowedTools() {
    return List.of();
  }
}

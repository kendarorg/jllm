package org.kendar.jllm.base;

import org.kendar.jllm.exceptions.LLMToolException;

import java.util.Map;

public interface LLMTool {
  boolean available();
  boolean requiresApproval();
  String name();
  String toolSchema();
  String act(Map<String,String> args) throws LLMToolException;

  /**
   * Name of an argument this tool can derive from the name it was invoked under
   * when that name is an alias rather than the canonical tool name. Used so a
   * tool reachable under many aliases (e.g. one delegate tool exposed under each
   * sub-agent name) can recover the selecting argument the model folded into the
   * tool name. Returns {@code null} when the tool derives nothing from its name.
   */
  default String nameDerivedArg() {
    return null;
  }
}

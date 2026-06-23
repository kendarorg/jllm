package org.kendar.jllm.base;

import org.kendar.jllm.exceptions.LLMToolException;

import java.util.Map;

public interface LLMTool {
  boolean available();
  boolean requiresApproval();
  String name();
  String toolSchema();
  String act(Map<String,String> args) throws LLMToolException;
}

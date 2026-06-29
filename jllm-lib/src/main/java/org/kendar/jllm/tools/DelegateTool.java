package org.kendar.jllm.tools;

import org.kendar.jllm.base.LLMAgent;
import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;
import org.kendar.jllm.session.AgenticLoop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Delegates a task to a named sub-agent. The sub-agent runs a nested
 * {@link AgenticLoop} with a tool registry filtered to its allow-list and a
 * fresh message history. Only the sub-agent's final answer is returned to the
 * parent; its transcript is not persisted. Recursion is depth-capped.
 */
public class DelegateTool implements LLMTool {

  private final LLMClient client;
  private final Path workingDir;
  private final LLMToolRegistry parentRegistry;
  private final int maxDepth;
  private final int depth;
  private final int maxIterations;

  public DelegateTool(LLMClient client, Path workingDir, LLMToolRegistry parentRegistry,
                      int maxDepth, int depth, int maxIterations) {
    this.client = client;
    this.workingDir = workingDir;
    this.parentRegistry = parentRegistry;
    this.maxDepth = maxDepth;
    this.depth = depth;
    this.maxIterations = maxIterations;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    return false;
  }

  @Override
  public String name() {
    return "task";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder(name(),
            "Delegate a task to a specialized sub-agent and get back its final answer.")
        .prop("agent", "string", "The name of the sub-agent to delegate to.", true)
        .prop("task", "string", "The task/prompt for the sub-agent.", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    if (depth >= maxDepth) {
      return "Delegation refused: maximum sub-agent depth (" + maxDepth + ") reached.";
    }
    String agentName = args == null ? null : args.get("agent");
    String task = args == null ? null : args.get("task");
    if (agentName == null || agentName.isEmpty()) {
      throw new LLMToolException("Missing required argument: agent");
    }
    if (task == null || task.isEmpty()) {
      throw new LLMToolException("Missing required argument: task");
    }

    LLMAgent agent;
    try {
      agent = LLMConfigManager.getAgent(agentName);
    } catch (RuntimeException e) {
      return "Unknown agent: " + agentName;
    }

    LLMToolRegistry subRegistry = parentRegistry.filtered(agent.getAllowedTools());
    // Give the sub-agent its own depth-incremented delegate tool.
    subRegistry.register(new DelegateTool(client, workingDir, parentRegistry,
        maxDepth, depth + 1, maxIterations));

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("system", agent.getSystemPrompt()));
    messages.add(new LLMMessage("user", task));

    try {
      AgenticLoop.LoopResult result = new AgenticLoop().run(client, messages, subRegistry, maxIterations);
      return result.finalText;
    } catch (Exception e) {
      throw new LLMToolException("Sub-agent '" + agentName + "' failed: " + e.getMessage(), e);
    }
  }
}

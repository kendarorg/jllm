package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMAgent;
import org.kendar.jllm.base.LLMClassifier;
import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.commons.LLMContext;
import org.kendar.jllm.exceptions.LLMConfigManagerException;
import org.kendar.jllm.chat.ChatMessage;
import org.kendar.jllm.chat.ChatStore;
import org.kendar.jllm.context.ContextFileLoader;
import org.kendar.jllm.exceptions.LLMClientException;
import org.kendar.jllm.mcp.McpManager;
import org.kendar.jllm.skills.SkillRegistry;
import org.kendar.jllm.tools.LLMToolRegistry;
import org.kendar.jllm.tools.TodoStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * An interactive coding session bound to a persisted conversation. Each
 * {@link #send(String)} rebuilds the message history (system prompt + stored
 * turns), runs the {@link AgenticLoop} and persists the new turns.
 */
public class CodingSession implements AutoCloseable {

  private final Path workingDir;
  private final LLMClient client;
  private final ChatStore store;
  private final LLMToolRegistry registry;
  private final LLMToolRegistry readOnlyRegistry;
  private final SkillRegistry skills;
  private final ContextFileLoader contextLoader;
  private final String conversationId;
  private final LLMAgent rootAgent;
  private final int maxIterations;
  private final PlanState planState;
  private final TodoStore todoStore;
  private final ContextCompressor compressor;
  private final McpManager mcpManager;

  /** Backwards-compatible constructor: no plan mode, no compression, default todo store. */
  public CodingSession(Path workingDir, LLMClient client, ChatStore store,
                       LLMToolRegistry registry, SkillRegistry skills,
                       ContextFileLoader contextLoader, String conversationId,
                       LLMAgent rootAgent, int maxIterations) {
    this(workingDir, client, store, registry, registry, skills, contextLoader, conversationId,
        rootAgent, maxIterations, new PlanState(false), new TodoStore(), null, null);
  }

  public CodingSession(Path workingDir, LLMClient client, ChatStore store,
                       LLMToolRegistry registry, LLMToolRegistry readOnlyRegistry,
                       SkillRegistry skills, ContextFileLoader contextLoader, String conversationId,
                       LLMAgent rootAgent, int maxIterations, PlanState planState,
                       TodoStore todoStore, ContextCompressor compressor, McpManager mcpManager) {
    this.workingDir = workingDir.toAbsolutePath().normalize();
    this.client = client;
    this.store = store;
    this.registry = registry;
    this.readOnlyRegistry = readOnlyRegistry == null ? registry : readOnlyRegistry;
    this.skills = skills;
    this.contextLoader = contextLoader;
    this.conversationId = conversationId;
    this.rootAgent = rootAgent;
    this.maxIterations = maxIterations;
    this.planState = planState == null ? new PlanState(false) : planState;
    this.todoStore = todoStore == null ? new TodoStore() : todoStore;
    this.compressor = compressor;
    this.mcpManager = mcpManager;
  }

  public boolean isPlanMode() {
    return planState.isActive();
  }

  /** Releases external resources (MCP server processes). Safe to call repeatedly. */
  @Override
  public void close() {
    if (mcpManager != null) {
      mcpManager.close();
    }
  }

  public String getConversationId() {
    return conversationId;
  }

  public String send(String userMessage) throws LLMClientException {
    appendStore("user", userMessage, null, null);

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("system", assembleSystemPrompt()));
    for (ChatMessage cm : store.getMessages(conversationId)) {
      LLMMessage m = new LLMMessage(cm.getRole(), cm.getContent());
      m.setToolName(cm.getToolName());
      m.setToolCallId(cm.getToolCallId());
      messages.add(m);
    }

    LLMToolRegistry activeRegistry = planState.isActive() ? readOnlyRegistry : registry;
    AgenticLoop.LoopResult result =
        new AgenticLoop(compressor, buildContinuationPolicy())
            .run(client, messages, activeRegistry, maxIterations);

    for (LLMMessage m : result.newMessages) {
      appendStore(m.getRole(), m.getContent(), m.getToolName(), m.getToolCallId());
    }
    return result.finalText;
  }

  /**
   * Builds the policy that keeps the agentic loop going when a weak model
   * narrates a next step without firing a tool. Deterministic on pending todos,
   * with an optional {@code continuationClassifier} fallback when it is configured.
   */
  private ContinuationPolicy buildContinuationPolicy() {
    LLMClassifier classifier;
    try {
      classifier = LLMConfigManager.getClassifier("continuationClassifier");
    } catch (LLMConfigManagerException e) {
      classifier = null; // optional: no continuation classifier configured
    }
    LLMContext context = new LLMContext();
    context.setClient(client);
    return new TodoContinuationPolicy(todoStore, classifier, context);
  }

  private void appendStore(String role, String content, String toolName, String toolCallId) {
    ChatMessage cm = new ChatMessage();
    cm.setConversationId(conversationId);
    cm.setRole(role);
    cm.setContent(content);
    cm.setToolName(toolName);
    cm.setToolCallId(toolCallId);
    cm.setCreatedAt(System.currentTimeMillis());
    store.appendMessage(cm);
  }

  String assembleSystemPrompt() {
    List<String> parts = new ArrayList<>();

    if (rootAgent != null) {
      String persona = rootAgent.getSystemPrompt();
      if (persona != null && !persona.isBlank()) {
        parts.add(persona);
      }
    }

    StringBuilder dir = new StringBuilder("Working directory: ").append(workingDir).append('\n');
    dir.append("Top-level entries:\n");
    try (Stream<Path> list = Files.list(workingDir)) {
      list.limit(50).forEach(p -> dir.append("- ").append(p.getFileName()).append('\n'));
    } catch (IOException ignored) {
      // directory not listable -> skip the snapshot
    }
    parts.add(dir.toString().trim());

    String context = contextLoader.load();
    if (context != null && !context.isBlank()) {
      parts.add(context.trim());
    }

    String catalog = skills.catalog();
    if (catalog != null && !catalog.isBlank()) {
      parts.add("Available skills:\n" + catalog.trim());
    }

    String agentCatalog = agentCatalog();
    if (!agentCatalog.isBlank()) {
      parts.add("You can delegate work to specialized sub-agents with the 'task' tool "
          + "(arguments: agent, task). Pick the agent whose description best fits the sub-task.\n"
          + "Available agents:\n" + agentCatalog);
    }

    if (mcpManager != null) {
      String resources = mcpManager.resourcesCatalog();
      if (!resources.isBlank()) {
        parts.add("MCP resources (read with 'read_mcp_resource'):\n" + resources);
      }
      String prompts = mcpManager.promptsCatalog();
      if (!prompts.isBlank()) {
        parts.add("MCP prompts (expand with 'get_mcp_prompt'; * marks required args):\n" + prompts);
      }
    }

    if (!todoStore.isEmpty()) {
      parts.add("Current todo list (keep it updated with 'todo_write'):\n" + todoStore.render());
    }

    if (planState.isActive()) {
      parts.add("PLAN MODE IS ACTIVE. Research the codebase using read-only tools and produce a "
          + "concrete implementation plan. Do NOT modify any files or run mutating commands. When "
          + "the plan is ready, call 'exit_plan_mode' with the plan to leave plan mode.");
    }

    parts.add("Tools are available; call them to inspect or modify the project.");

    return String.join("\n\n", parts);
  }

  /** Lists registered sub-agents (excluding the current root agent) for auto-delegation. */
  private String agentCatalog() {
    String rootName = rootAgent == null ? null : rootAgent.getName();
    StringBuilder sb = new StringBuilder();
    for (LLMAgent agent : LLMConfigManager.listAgents()) {
      if (rootName != null && rootName.equals(agent.getName())) {
        continue;
      }
      String desc = agent.getDescription();
      sb.append("- ").append(agent.getName());
      if (desc != null && !desc.isBlank()) {
        sb.append(": ").append(desc.trim().replaceAll("\\s+", " "));
      }
      sb.append('\n');
    }
    return sb.toString().trim();
  }
}

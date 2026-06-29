package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMAgent;
import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.chat.ChatStore;
import org.kendar.jllm.chat.Conversation;
import org.kendar.jllm.context.ContextFileLoader;
import org.kendar.jllm.mcp.McpManager;
import org.kendar.jllm.skills.SkillRegistry;
import org.kendar.jllm.skills.SkillTool;
import org.kendar.jllm.tools.DelegateTool;
import org.kendar.jllm.tools.ExitPlanModeTool;
import org.kendar.jllm.tools.LLMToolRegistry;
import org.kendar.jllm.tools.SaveMemoryTool;
import org.kendar.jllm.tools.TodoStore;
import org.kendar.jllm.tools.TodoWriteTool;

import java.nio.file.Path;
import java.util.List;

/** Wires up a fully-equipped {@link CodingSession} (tools, skills, context, sub-agents). */
public final class CodingSessionFactory {

  private static final int MAX_ITERATIONS = 20;
  private static final int MAX_DELEGATE_DEPTH = 3;

  // Default context window (tokens) and the fraction at which we start compressing.
  private static final int CONTEXT_TOKENS = 128 * 1000;
  private static final double COMPRESS_TRIGGER_RATIO = 0.75;
  private static final int KEEP_RECENT_MESSAGES = 6;

  /** Tools that never mutate the project; the only ones exposed while in plan mode. */
  private static final List<String> READ_ONLY_TOOLS = List.of(
      "read_file", "list_directory", "glob", "search_file_content", "read_many_files",
      "web_fetch", "web_search", "skill", "todo_write", "exit_plan_mode");

  private CodingSessionFactory() {
  }

  public static CodingSession create(Path workingDir, LLMClient client, ChatStore store,
                                     String title, String rootAgentName) {
    return create(workingDir, client, store, title, rootAgentName, false);
  }

  public static CodingSession create(Path workingDir, LLMClient client, ChatStore store,
                                     String title, String rootAgentName, boolean planMode) {
    Conversation conversation = store.createConversation(title, workingDir.toAbsolutePath().toString());
    return build(workingDir, client, store, conversation.getId(), rootAgentName, planMode);
  }

  public static CodingSession resume(Path workingDir, LLMClient client, ChatStore store,
                                     String conversationId, String rootAgentName) {
    Conversation conversation = store.getConversation(conversationId);
    if (conversation == null) {
      throw new IllegalArgumentException("Unknown conversation: " + conversationId);
    }
    return build(workingDir, client, store, conversationId, rootAgentName, false);
  }

  private static CodingSession build(Path workingDir, LLMClient client, ChatStore store,
                                     String conversationId, String rootAgentName, boolean planMode) {
    ContextFileLoader contextLoader = new ContextFileLoader(workingDir, List.of());

    SkillRegistry skills = new SkillRegistry();
    skills.discover(workingDir, List.of());

    PlanState planState = new PlanState(planMode);
    TodoStore todoStore = new TodoStore();

    LLMToolRegistry registry = LLMToolRegistry.withDefaults(workingDir);
    registry.register(new SaveMemoryTool(contextLoader.memoryFile()));
    SkillTool skillTool = new SkillTool(skills);
    registry.register(skillTool);
    registry.registerAlias("use_skill", skillTool.name());
    registry.register(new TodoWriteTool(todoStore));
    registry.register(new ExitPlanModeTool(planState));
    registry.register(new DelegateTool(client, workingDir, registry,
        MAX_DELEGATE_DEPTH, 0, MAX_ITERATIONS));

    // External MCP servers (if any configured) contribute extra tools.
    McpManager mcpManager = McpManager.loadAndRegister(workingDir, registry);

    // The read-only view used while plan mode is active (built after all tools exist).
    LLMToolRegistry readOnlyRegistry = registry.filtered(READ_ONLY_TOOLS);

    ContextCompressor compressor = new ContextCompressor(
        client, CONTEXT_TOKENS, COMPRESS_TRIGGER_RATIO, KEEP_RECENT_MESSAGES);

    LLMAgent rootAgent = null;
    if (rootAgentName != null) {
      try {
        rootAgent = LLMConfigManager.getAgent(rootAgentName);
      } catch (RuntimeException ignored) {
        rootAgent = null;
      }
    }

    return new CodingSession(workingDir, client, store, registry, readOnlyRegistry, skills,
        contextLoader, conversationId, rootAgent, MAX_ITERATIONS, planState, todoStore,
        compressor, mcpManager);
  }
}

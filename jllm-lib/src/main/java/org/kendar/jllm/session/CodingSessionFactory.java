package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMAgent;
import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.chat.ChatStore;
import org.kendar.jllm.chat.Conversation;
import org.kendar.jllm.context.ContextFileLoader;
import org.kendar.jllm.skills.SkillRegistry;
import org.kendar.jllm.skills.SkillTool;
import org.kendar.jllm.tools.DelegateTool;
import org.kendar.jllm.tools.LLMToolRegistry;
import org.kendar.jllm.tools.SaveMemoryTool;

import java.nio.file.Path;
import java.util.List;

/** Wires up a fully-equipped {@link CodingSession} (tools, skills, context, sub-agents). */
public final class CodingSessionFactory {

  private static final int MAX_ITERATIONS = 20;
  private static final int MAX_DELEGATE_DEPTH = 3;

  private CodingSessionFactory() {
  }

  public static CodingSession create(Path workingDir, LLMClient client, ChatStore store,
                                     String title, String rootAgentName) {
    Conversation conversation = store.createConversation(title, workingDir.toAbsolutePath().toString());
    return build(workingDir, client, store, conversation.getId(), rootAgentName);
  }

  public static CodingSession resume(Path workingDir, LLMClient client, ChatStore store,
                                     String conversationId, String rootAgentName) {
    Conversation conversation = store.getConversation(conversationId);
    if (conversation == null) {
      throw new IllegalArgumentException("Unknown conversation: " + conversationId);
    }
    return build(workingDir, client, store, conversationId, rootAgentName);
  }

  private static CodingSession build(Path workingDir, LLMClient client, ChatStore store,
                                     String conversationId, String rootAgentName) {
    ContextFileLoader contextLoader = new ContextFileLoader(workingDir, List.of());

    SkillRegistry skills = new SkillRegistry();
    skills.discover(workingDir, List.of());

    LLMToolRegistry registry = LLMToolRegistry.withDefaults(workingDir);
    registry.register(new SaveMemoryTool(contextLoader.memoryFile()));
    SkillTool skillTool = new SkillTool(skills);
    registry.register(skillTool);
    registry.registerAlias("use_skill", skillTool.name());
    registry.register(new DelegateTool(client, workingDir, registry,
        MAX_DELEGATE_DEPTH, 0, MAX_ITERATIONS));

    LLMAgent rootAgent = null;
    if (rootAgentName != null) {
      try {
        rootAgent = LLMConfigManager.getAgent(rootAgentName);
      } catch (RuntimeException ignored) {
        rootAgent = null;
      }
    }

    return new CodingSession(workingDir, client, store, registry, skills,
        contextLoader, conversationId, rootAgent, MAX_ITERATIONS);
  }
}

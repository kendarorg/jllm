package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMAgent;
import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.chat.ChatMessage;
import org.kendar.jllm.chat.ChatStore;
import org.kendar.jllm.context.ContextFileLoader;
import org.kendar.jllm.exceptions.LLMClientException;
import org.kendar.jllm.skills.SkillRegistry;
import org.kendar.jllm.tools.LLMToolRegistry;

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
public class CodingSession {

  private final Path workingDir;
  private final LLMClient client;
  private final ChatStore store;
  private final LLMToolRegistry registry;
  private final SkillRegistry skills;
  private final ContextFileLoader contextLoader;
  private final String conversationId;
  private final LLMAgent rootAgent;
  private final int maxIterations;

  public CodingSession(Path workingDir, LLMClient client, ChatStore store,
                       LLMToolRegistry registry, SkillRegistry skills,
                       ContextFileLoader contextLoader, String conversationId,
                       LLMAgent rootAgent, int maxIterations) {
    this.workingDir = workingDir.toAbsolutePath().normalize();
    this.client = client;
    this.store = store;
    this.registry = registry;
    this.skills = skills;
    this.contextLoader = contextLoader;
    this.conversationId = conversationId;
    this.rootAgent = rootAgent;
    this.maxIterations = maxIterations;
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

    AgenticLoop.LoopResult result = new AgenticLoop().run(client, messages, registry, maxIterations);

    for (LLMMessage m : result.newMessages) {
      appendStore(m.getRole(), m.getContent(), m.getToolName(), m.getToolCallId());
    }
    return result.finalText;
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

    parts.add("Tools are available; call them to inspect or modify the project.");

    return String.join("\n\n", parts);
  }
}

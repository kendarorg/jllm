package org.kendar.jllm.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.jllm.chat.ChatMessage;
import org.kendar.jllm.chat.Conversation;
import org.kendar.jllm.chat.H2ChatStore;
import org.kendar.jllm.context.ContextFileLoader;
import org.kendar.jllm.skills.SkillRegistry;
import org.kendar.jllm.tools.LLMToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodingSessionTest {

  @Test
  void runsToolThenPersistsTurns(@TempDir Path workingDir) throws Exception {
    H2ChatStore store = H2ChatStore.inMemory();
    Conversation conversation = store.createConversation("t", workingDir.toString());

    LLMToolRegistry registry = LLMToolRegistry.withDefaults(workingDir);
    SkillRegistry skills = new SkillRegistry();
    ContextFileLoader contextLoader = new ContextFileLoader(workingDir, List.of());

    StubClient client = new StubClient()
        .toolCall("write_file", Map.of("path", "hello.txt", "content", "hi"))
        .text("done");

    CodingSession session = new CodingSession(workingDir, client, store, registry, skills,
        contextLoader, conversation.getId(), null, 10);

    String result = session.send("create hello.txt");

    assertEquals("done", result);
    Path file = workingDir.resolve("hello.txt");
    assertTrue(Files.exists(file));
    assertEquals("hi", Files.readString(file));

    List<ChatMessage> messages = store.getMessages(conversation.getId());
    assertEquals(4, messages.size());
    assertEquals("user", messages.get(0).getRole());
    assertEquals("assistant", messages.get(1).getRole());
    assertEquals("tool", messages.get(2).getRole());
    assertEquals("write_file", messages.get(2).getToolName());
    assertEquals("assistant", messages.get(3).getRole());
    assertEquals("done", messages.get(3).getContent());
  }
}

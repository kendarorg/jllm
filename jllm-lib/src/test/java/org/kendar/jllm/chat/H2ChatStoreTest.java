package org.kendar.jllm.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class H2ChatStoreTest {

  @Test
  void createAndRoundTripConversation() {
    try (var store = H2ChatStore.inMemory()) {
      var conv = store.createConversation("My chat", "/work/dir");
      assertNotNull(conv.getId());
      assertEquals("My chat", conv.getTitle());
      assertEquals("/work/dir", conv.getWorkingDir());
      assertTrue(conv.getCreatedAt() > 0);
      assertTrue(conv.getUpdatedAt() > 0);

      var fetched = store.getConversation(conv.getId());
      assertNotNull(fetched);
      assertEquals(conv.getId(), fetched.getId());
      assertEquals("My chat", fetched.getTitle());
      assertEquals("/work/dir", fetched.getWorkingDir());
      assertEquals(conv.getCreatedAt(), fetched.getCreatedAt());

      var all = store.listConversations();
      assertTrue(all.stream().anyMatch(c -> c.getId().equals(conv.getId())));
    }
  }

  @Test
  void appendAndReadMessagesInOrder() throws InterruptedException {
    try (var store = H2ChatStore.inMemory()) {
      var conv = store.createConversation("c", "/d");

      var user = new ChatMessage();
      user.setConversationId(conv.getId());
      user.setRole("user");
      user.setContent("hello");
      store.appendMessage(user);

      var assistant = new ChatMessage();
      assistant.setConversationId(conv.getId());
      assistant.setRole("assistant");
      assistant.setContent("hi there");
      store.appendMessage(assistant);

      var tool = new ChatMessage();
      tool.setConversationId(conv.getId());
      tool.setRole("tool");
      tool.setContent("result");
      tool.setToolName("Bash");
      tool.setToolCallId("call_1");
      tool.setThinking("considering");
      store.appendMessage(tool);

      List<ChatMessage> messages = store.getMessages(conv.getId());
      assertEquals(3, messages.size());
      assertEquals("user", messages.get(0).getRole());
      assertEquals("hello", messages.get(0).getContent());
      assertEquals("assistant", messages.get(1).getRole());
      assertEquals("tool", messages.get(2).getRole());
      assertEquals("Bash", messages.get(2).getToolName());
      assertEquals("call_1", messages.get(2).getToolCallId());
      assertEquals("considering", messages.get(2).getThinking());
      assertTrue(messages.get(0).getId() < messages.get(1).getId());
      assertTrue(messages.get(1).getId() < messages.get(2).getId());
      assertTrue(messages.get(0).getCreatedAt() > 0);
    }
  }

  @Test
  void appendBumpsUpdatedAt() throws InterruptedException {
    try (var store = H2ChatStore.inMemory()) {
      var conv = store.createConversation("c", "/d");
      var original = conv.getUpdatedAt();
      Thread.sleep(2);

      var msg = new ChatMessage();
      msg.setConversationId(conv.getId());
      msg.setRole("user");
      msg.setContent("x");
      store.appendMessage(msg);

      var reloaded = store.getConversation(conv.getId());
      assertTrue(reloaded.getUpdatedAt() >= original);
      assertTrue(reloaded.getUpdatedAt() >= reloaded.getCreatedAt());
    }
  }

  @Test
  void unknownLookups() {
    try (var store = H2ChatStore.inMemory()) {
      assertNull(store.getConversation("nope"));
      assertTrue(store.getMessages("nope").isEmpty());
    }
  }

  @Test
  void storesAreIsolated() {
    try (var a = H2ChatStore.inMemory(); var b = H2ChatStore.inMemory()) {
      var conv = a.createConversation("only-in-a", "/d");
      assertNull(b.getConversation(conv.getId()));
      assertTrue(b.listConversations().isEmpty());
    }
  }
}

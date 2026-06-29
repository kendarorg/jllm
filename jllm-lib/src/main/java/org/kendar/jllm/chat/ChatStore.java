package org.kendar.jllm.chat;

import java.util.List;

public interface ChatStore {
  Conversation createConversation(String title, String workingDir);

  Conversation getConversation(String id);

  List<Conversation> listConversations();

  void appendMessage(ChatMessage message);

  List<ChatMessage> getMessages(String conversationId);
}

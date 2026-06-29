package org.kendar.jllm.chat;

import org.kendar.jllm.exceptions.LLMChatStoreException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class H2ChatStore implements ChatStore, AutoCloseable {
  private static final AtomicLong COUNTER = new AtomicLong();

  private final Connection connection;

  public H2ChatStore(String jdbcUrl) {
    try {
      this.connection = DriverManager.getConnection(jdbcUrl);
      initSchema();
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to open chat store", e);
    }
  }

  public static H2ChatStore inMemory() {
    var name = "jllm_" + COUNTER.incrementAndGet() + "_" + UUID.randomUUID().toString().replace("-", "");
    return new H2ChatStore("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
  }

  private void initSchema() {
    try (Statement st = connection.createStatement()) {
      st.execute("CREATE TABLE IF NOT EXISTS conversations (" +
          "id VARCHAR(64) PRIMARY KEY, " +
          "title VARCHAR(1024), " +
          "working_dir VARCHAR(4096), " +
          "created_at BIGINT, " +
          "updated_at BIGINT)");
      st.execute("CREATE TABLE IF NOT EXISTS chat_messages (" +
          "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
          "conversation_id VARCHAR(64), " +
          "role VARCHAR(64), " +
          "content CLOB, " +
          "tool_name VARCHAR(256), " +
          "tool_call_id VARCHAR(256), " +
          "thinking CLOB, " +
          "created_at BIGINT)");
      st.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation " +
          "ON chat_messages(conversation_id)");
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to create chat store schema", e);
    }
  }

  @Override
  public Conversation createConversation(String title, String workingDir) {
    var now = System.currentTimeMillis();
    var conversation = new Conversation();
    conversation.setId(UUID.randomUUID().toString());
    conversation.setTitle(title);
    conversation.setWorkingDir(workingDir);
    conversation.setCreatedAt(now);
    conversation.setUpdatedAt(now);
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO conversations (id, title, working_dir, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")) {
      ps.setString(1, conversation.getId());
      ps.setString(2, conversation.getTitle());
      ps.setString(3, conversation.getWorkingDir());
      ps.setLong(4, conversation.getCreatedAt());
      ps.setLong(5, conversation.getUpdatedAt());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to create conversation", e);
    }
    return conversation;
  }

  @Override
  public Conversation getConversation(String id) {
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT id, title, working_dir, created_at, updated_at FROM conversations WHERE id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return mapConversation(rs);
        }
        return null;
      }
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to read conversation", e);
    }
  }

  @Override
  public List<Conversation> listConversations() {
    var result = new ArrayList<Conversation>();
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT id, title, working_dir, created_at, updated_at FROM conversations ORDER BY updated_at DESC");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        result.add(mapConversation(rs));
      }
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to list conversations", e);
    }
    return result;
  }

  @Override
  public void appendMessage(ChatMessage message) {
    if (message.getCreatedAt() == 0) {
      message.setCreatedAt(System.currentTimeMillis());
    }
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO chat_messages (conversation_id, role, content, tool_name, tool_call_id, thinking, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, message.getConversationId());
      ps.setString(2, message.getRole());
      ps.setString(3, message.getContent());
      ps.setString(4, message.getToolName());
      ps.setString(5, message.getToolCallId());
      ps.setString(6, message.getThinking());
      ps.setLong(7, message.getCreatedAt());
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (keys.next()) {
          message.setId(keys.getLong(1));
        }
      }
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to append message", e);
    }
    try (PreparedStatement ps = connection.prepareStatement(
        "UPDATE conversations SET updated_at = ? WHERE id = ?")) {
      ps.setLong(1, message.getCreatedAt());
      ps.setString(2, message.getConversationId());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to bump conversation", e);
    }
  }

  @Override
  public List<ChatMessage> getMessages(String conversationId) {
    var result = new ArrayList<ChatMessage>();
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT id, conversation_id, role, content, tool_name, tool_call_id, thinking, created_at " +
            "FROM chat_messages WHERE conversation_id = ? ORDER BY id ASC")) {
      ps.setString(1, conversationId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          var m = new ChatMessage();
          m.setId(rs.getLong("id"));
          m.setConversationId(rs.getString("conversation_id"));
          m.setRole(rs.getString("role"));
          m.setContent(rs.getString("content"));
          m.setToolName(rs.getString("tool_name"));
          m.setToolCallId(rs.getString("tool_call_id"));
          m.setThinking(rs.getString("thinking"));
          m.setCreatedAt(rs.getLong("created_at"));
          result.add(m);
        }
      }
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to read messages", e);
    }
    return result;
  }

  private Conversation mapConversation(ResultSet rs) throws SQLException {
    var c = new Conversation();
    c.setId(rs.getString("id"));
    c.setTitle(rs.getString("title"));
    c.setWorkingDir(rs.getString("working_dir"));
    c.setCreatedAt(rs.getLong("created_at"));
    c.setUpdatedAt(rs.getLong("updated_at"));
    return c;
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new LLMChatStoreException("Unable to close chat store", e);
    }
  }
}

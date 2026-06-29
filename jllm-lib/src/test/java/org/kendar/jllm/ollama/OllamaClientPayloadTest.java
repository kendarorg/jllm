package org.kendar.jllm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.kendar.jllm.LLMSettings;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.base.LLMRequest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OllamaClientPayloadTest {

  private LLMSettings settings() {
    LLMSettings settings = new LLMSettings();
    settings.setModel("llama3");
    settings.setServer("http://localhost:1");
    return settings;
  }

  @Test
  void buildsPayloadFromMessagesAndTools() throws Exception {
    OllamaClient client = new OllamaClient(settings());

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("system", "You are helpful"));
    messages.add(new LLMMessage("user", "Read the file"));
    messages.add(new LLMMessage("assistant", "Sure"));
    LLMMessage toolMsg = new LLMMessage("tool", "file contents");
    toolMsg.setToolName("read_file");
    messages.add(toolMsg);

    ObjectNode toolSchema = LLMObjectMapper.getObjectMapper().createObjectNode();
    toolSchema.put("type", "function");
    ObjectNode fn = toolSchema.putObject("function");
    fn.put("name", "read_file");

    LLMRequest request = new LLMRequest();
    request.setMessages(messages);
    request.setTools(List.of(toolSchema));
    request.setFormat("");

    String payload = client.buildPayload(request);
    JsonNode root = LLMObjectMapper.getObjectMapper().readTree(payload);

    JsonNode msgs = root.get("messages");
    assertEquals(4, msgs.size());
    assertEquals("system", msgs.get(0).get("role").asText());
    assertEquals("You are helpful", msgs.get(0).get("content").asText());
    assertEquals("user", msgs.get(1).get("role").asText());
    assertEquals("Read the file", msgs.get(1).get("content").asText());
    assertEquals("assistant", msgs.get(2).get("role").asText());
    assertEquals("tool", msgs.get(3).get("role").asText());
    assertEquals("read_file", msgs.get(3).get("tool_name").asText());

    JsonNode tools = root.get("tools");
    assertNotNull(tools);
    assertEquals(1, tools.size());
    assertEquals("read_file", tools.get(0).get("function").get("name").asText());

    assertFalse(root.has("format"), "format should be absent when empty");
  }

  @Test
  void fallsBackToPromptAndSystemWhenNoMessages() throws Exception {
    OllamaClient client = new OllamaClient(settings());

    LLMRequest request = new LLMRequest();
    request.setSystem("You are a helpful assistant");
    request.setPrompt("Hello there");

    String payload = client.buildPayload(request);
    JsonNode root = LLMObjectMapper.getObjectMapper().readTree(payload);

    JsonNode msgs = root.get("messages");
    assertEquals(2, msgs.size());
    assertEquals("system", msgs.get(0).get("role").asText());
    assertEquals("You are a helpful assistant", msgs.get(0).get("content").asText());
    assertEquals("user", msgs.get(1).get("role").asText());
    assertEquals("Hello there", msgs.get(1).get("content").asText());
  }
}

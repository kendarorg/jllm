package org.kendar.jllm;

import org.junit.jupiter.api.Test;
import org.kendar.jllm.base.LLMRequest;
import org.kendar.jllm.exceptions.LLMClientException;
import org.kendar.jllm.ollama.OllamaClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These tests do not require a running Ollama instance. Since there is no
 * listening server, a real {@link OllamaClient#call} is expected to fail with a
 * {@link RuntimeException} wrapping the connection error.
 */
class OllamaClientTest {

  private LLMSettings settings() {
    LLMSettings settings = new LLMSettings();
    settings.setModel("llama3");
    // Point at a port where nothing is listening.
    settings.setServer("http://localhost:1");
    return settings;
  }

  private LLMRequest request() {
    LLMRequest request = new LLMRequest();
    request.setPrompt("Hello there");
    request.setSystem("You are a helpful assistant");
    return request;
  }

  @Test
  void callFailsWhenNoServerIsListening() {
    OllamaClient client = new OllamaClient(settings());
    RuntimeException ex = assertThrows(LLMClientException.class, () -> client.call(request()));
    assertNotNull(ex);
  }

  @Test
  void callFailsWithDefaultServerWhenOllamaIsDown() {
    LLMSettings settings = new LLMSettings();
    settings.setModel("llama3");
    // No server set -> defaults to http://localhost:11434, which is not running.
    OllamaClient client = new OllamaClient(settings);
    assertThrows(LLMClientException.class, () -> client.call(request()));
  }

  @Test
  void clientCanBeConstructed() {
    assertNotNull(new OllamaClient(settings()));
  }
}

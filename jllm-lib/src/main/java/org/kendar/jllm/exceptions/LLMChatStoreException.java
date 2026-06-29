package org.kendar.jllm.exceptions;

public class LLMChatStoreException extends RuntimeException {
  public LLMChatStoreException(String message) {
    super(message);
  }

  public LLMChatStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}

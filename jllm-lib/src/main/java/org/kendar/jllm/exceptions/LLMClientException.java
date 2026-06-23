package org.kendar.jllm.exceptions;

public class LLMClientException extends RuntimeException{
  public LLMClientException(RuntimeException e) {
    super(e);
  }

  public LLMClientException(String message, Exception e) {
    super(message, e);
  }
}

package org.kendar.jllm.exceptions;

public class LLMToolException extends RuntimeException{
  public LLMToolException() {
    super();
  }

  public LLMToolException(String message) {
    super(message);
  }

  public LLMToolException(String message, Throwable cause) {
    super(message, cause);
  }
}

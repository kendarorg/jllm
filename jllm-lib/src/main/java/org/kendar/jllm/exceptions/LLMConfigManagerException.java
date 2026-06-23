package org.kendar.jllm.exceptions;

public class LLMConfigManagerException extends RuntimeException{
  public LLMConfigManagerException(String text) {
    super(text);
  }

  public LLMConfigManagerException(String text, Throwable cause) {
    super(text, cause);
  }
}

package org.kendar.jllm.exceptions;

public class LLMClassificationException extends RuntimeException{
  public LLMClassificationException(String text) {
    super(text);
  }
}

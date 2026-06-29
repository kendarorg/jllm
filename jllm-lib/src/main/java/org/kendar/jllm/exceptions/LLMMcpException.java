package org.kendar.jllm.exceptions;

public class LLMMcpException extends RuntimeException {
  public LLMMcpException(String message) {
    super(message);
  }

  public LLMMcpException(String message, Throwable cause) {
    super(message, cause);
  }
}

package org.kendar.jllm.base;

import org.kendar.jllm.exceptions.LLMClientException;

public interface LLMClient {
  LLMResponse call(LLMRequest request) throws LLMClientException;
}

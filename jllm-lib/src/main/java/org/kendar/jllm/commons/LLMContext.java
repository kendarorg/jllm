package org.kendar.jllm.commons;

import org.kendar.jllm.base.LLMClient;

public class LLMContext {
  public LLMClient getClient() {
    return client;
  }

  public void setClient(LLMClient client) {
    this.client = client;
  }

  private LLMClient client;
}

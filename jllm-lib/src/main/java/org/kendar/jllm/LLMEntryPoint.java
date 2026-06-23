package org.kendar.jllm;

import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMConfigClassifier;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.commons.LLMContext;

public class LLMEntryPoint {
  private final LLMClient client;
  private final LLMContext context;
  private final LLMConfigClassifier scopeClassifier;

  public LLMEntryPoint(LLMClient client){
    this.client = client;
    this.context = new LLMContext();
    this.context.setClient(client);
    this.scopeClassifier = LLMConfigManager.get("scopeClassifer", LLMConfigClassifier.class);
  }
  public void call(String prompt){
    prompt = prompt.trim();
    var result = this.scopeClassifier.classify(context,prompt).trim();


  }
}

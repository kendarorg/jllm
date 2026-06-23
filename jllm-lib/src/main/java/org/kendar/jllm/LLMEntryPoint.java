package org.kendar.jllm;

import org.kendar.jllm.base.*;
import org.kendar.jllm.commons.LLMContext;

public class LLMEntryPoint {
  private final LLMClient client;
  private final LLMContext context;
  private final LLMClassifier scopeClassifier;

  public LLMEntryPoint(LLMClient client){
    this.client = client;
    this.context = new LLMContext();
    this.context.setClient(client);
    this.scopeClassifier = LLMConfigManager.getClassifier("scopeClassifer");
  }
  public String call(String prompt){
    prompt = prompt.trim();
    var classification = this.scopeClassifier.classify(context,prompt);
    LLMResponse response = null;
    //Choose then what agent should it call between the standard root agents based on the scope
    switch (classification){
      case("plan"):
        break;
      case("oneShot"):
      default:
        response = client.call(new LLMRequest().withPrompt(prompt));
        return response.getResponse();
    }

    return response == null ? null : response.getResponse();
  }
}

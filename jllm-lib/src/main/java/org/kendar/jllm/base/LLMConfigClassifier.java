package org.kendar.jllm.base;

import org.kendar.jllm.commons.LLMContext;

import java.util.HashMap;
import java.util.Map;

public class LLMConfigClassifier extends LLMClassifier {
  private String description;
  private int retries=1;
  private String name;

  @Override
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  private Map<String,String> classification = new HashMap<>();

  public void setDescription(String description) {
    this.description = description;
  }

  public void setRetries(int retries) {
    this.retries = retries;
  }

  public void setClassification(Map<String, String> classification) {
    this.classification = classification;
  }

  @Override
  public String getDescription() {
    return this.description;
  }

  @Override
  public Map<String, String> getClassification() {
    return this.classification;
  }

  @Override
  public int getRetries() {
    return this.retries;
  }
}

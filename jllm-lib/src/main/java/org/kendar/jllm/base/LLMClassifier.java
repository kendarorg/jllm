package org.kendar.jllm.base;

import org.kendar.jllm.commons.LLMContext;
import org.kendar.jllm.exceptions.LLMClassificationException;

import java.util.HashMap;
import java.util.Map;

public abstract class LLMClassifier {
  public abstract String getName();
  public abstract String getDescription();
  public abstract Map<String,String> getClassification();
  public abstract int getRetries();

  public String classify(LLMContext execContext, String text){
    for(var i=0;i<getRetries();i++){
      var result = execContext.getClient().call(
          new LLMRequest().withPrompt(text)
      );
      var classification = isClassified(result.getResponse());
      if(classification==null){
        classification = isClassified(result.getThinking());
      }
      if(classification!=null){
        return classification.trim();
      }
    }
    throw new LLMClassificationException("Unable to classify prompt "+text);
  }
  public String buildPrompt(){
    return getDescription()+"\n"+
        "These are the only allowed values:\n"+
        getClassification().entrySet().stream().
            map(e->" * "+e.getKey()+": "+e.getValue()).
            reduce((a,b)->a+"\n"+b).orElse("")+"\n"+
        "Reply with exactly one word:"+"\n"+
        String.join(" or ",getClassification().keySet())+"\n"+
        "Classify the following text:\n";
  }

  protected String isClassified(String result){
    result = result.trim().toLowerCase();
    for(var c:getClassification().keySet()){
      if(result.equalsIgnoreCase(c))return c;
    }
    return null;
  }
}

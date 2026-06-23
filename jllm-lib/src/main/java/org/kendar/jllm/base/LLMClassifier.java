package org.kendar.jllm.base;

import java.util.HashMap;
import java.util.Map;

public interface LLMClassifier {
  Map<String,String> classification = new HashMap<>();
  String classify(String text);
}

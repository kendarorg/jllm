package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMMessage;

import java.util.List;

/**
 * Cheap, provider-agnostic token estimate. We deliberately avoid a real
 * tokenizer (which would be model-specific and pull in a dependency) and use the
 * well-known ~4-characters-per-token heuristic, which is good enough to drive
 * context-compression decisions.
 */
public final class TokenEstimator {

  private static final int CHARS_PER_TOKEN = 4;

  private TokenEstimator() {
  }

  public static int estimate(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
  }

  public static int estimate(LLMMessage message) {
    if (message == null) {
      return 0;
    }
    // A small per-message overhead for role/structure framing.
    return estimate(message.getContent()) + 4;
  }

  public static int estimate(List<LLMMessage> messages) {
    if (messages == null) {
      return 0;
    }
    int total = 0;
    for (LLMMessage m : messages) {
      total += estimate(m);
    }
    return total;
  }
}

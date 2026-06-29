package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.base.LLMRequest;
import org.kendar.jllm.base.LLMResponse;
import org.kendar.jllm.exceptions.LLMClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a long conversation inside the model's context window. When the
 * estimated token count of the message history crosses a fraction of the context
 * size, the older turns are summarized (via the LLM) into a single digest message
 * while the leading system prompt and the most recent turns are preserved
 * verbatim.
 */
public class ContextCompressor {

  private final LLMClient client;
  private final int contextTokens;
  private final double triggerRatio;
  private final int keepRecentMessages;

  /**
   * @param contextTokens      the model's context window in tokens (e.g. num_ctx)
   * @param triggerRatio       compress once the history exceeds this fraction of the window
   * @param keepRecentMessages number of most-recent messages always kept verbatim
   */
  public ContextCompressor(LLMClient client, int contextTokens, double triggerRatio,
                           int keepRecentMessages) {
    this.client = client;
    this.contextTokens = contextTokens;
    this.triggerRatio = triggerRatio;
    this.keepRecentMessages = Math.max(1, keepRecentMessages);
  }

  /** True if the given history is large enough to warrant compression. */
  public boolean shouldCompress(List<LLMMessage> messages) {
    if (messages == null) {
      return false;
    }
    int leading = leadingSystemCount(messages);
    // Need at least one message to summarize beyond the kept window.
    if (messages.size() - leading - keepRecentMessages < 2) {
      return false;
    }
    return TokenEstimator.estimate(messages) > (int) (contextTokens * triggerRatio);
  }

  /**
   * Returns a compressed copy of {@code messages}: leading system message(s), then
   * a single summary message, then the most recent {@code keepRecentMessages}.
   * If compression is not warranted the original list is returned unchanged.
   */
  public List<LLMMessage> compress(List<LLMMessage> messages) {
    if (!shouldCompress(messages)) {
      return messages;
    }
    int leading = leadingSystemCount(messages);
    int tailStart = messages.size() - keepRecentMessages;

    List<LLMMessage> head = messages.subList(0, leading);
    List<LLMMessage> middle = messages.subList(leading, tailStart);
    List<LLMMessage> tail = messages.subList(tailStart, messages.size());

    String summary = summarize(middle);

    List<LLMMessage> compressed = new ArrayList<>(head);
    compressed.add(new LLMMessage("system",
        "Summary of earlier conversation (older turns were compressed to save context):\n" + summary));
    compressed.addAll(tail);
    return compressed;
  }

  private String summarize(List<LLMMessage> middle) {
    StringBuilder transcript = new StringBuilder();
    for (LLMMessage m : middle) {
      String content = m.getContent() == null ? "" : m.getContent();
      transcript.append(m.getRole()).append(": ").append(content).append("\n\n");
    }

    String prompt = "Summarize the following conversation transcript between a user and a "
        + "coding agent. Preserve all decisions made, files created or modified, key facts "
        + "discovered, and any unfinished work or next steps. Be concise but lose no actionable "
        + "detail. Output only the summary.\n\n" + transcript;

    LLMRequest request = new LLMRequest();
    request.setFormat("");
    request.setSystem("You compress conversation histories without losing actionable detail.");
    request.setPrompt(prompt);
    try {
      LLMResponse response = client.call(request);
      String text = response.getResponse();
      if (text == null || text.isBlank()) {
        return fallbackSummary(middle);
      }
      return text.trim();
    } catch (LLMClientException e) {
      // Never let compression break the main loop; degrade to a structural digest.
      return fallbackSummary(middle);
    }
  }

  private String fallbackSummary(List<LLMMessage> middle) {
    StringBuilder sb = new StringBuilder("[" + middle.size() + " earlier messages omitted]\n");
    for (LLMMessage m : middle) {
      String content = m.getContent() == null ? "" : m.getContent();
      String oneLine = content.replaceAll("\\s+", " ").trim();
      if (oneLine.length() > 160) {
        oneLine = oneLine.substring(0, 160) + "...";
      }
      sb.append("- ").append(m.getRole()).append(": ").append(oneLine).append('\n');
    }
    return sb.toString().trim();
  }

  private static int leadingSystemCount(List<LLMMessage> messages) {
    int count = 0;
    for (LLMMessage m : messages) {
      if ("system".equals(m.getRole())) {
        count++;
      } else {
        break;
      }
    }
    return count;
  }
}

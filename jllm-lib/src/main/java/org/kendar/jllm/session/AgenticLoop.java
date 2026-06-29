package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.base.LLMRequest;
import org.kendar.jllm.base.LLMResponse;
import org.kendar.jllm.base.LLMToolCall;
import org.kendar.jllm.exceptions.LLMClientException;
import org.kendar.jllm.exceptions.LLMToolException;
import org.kendar.jllm.tools.LLMToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the bounded tool-calling loop: call the model, dispatch any requested
 * tools, feed results back, repeat until the model stops calling tools or the
 * iteration cap is reached.
 */
public class AgenticLoop {

  private final ContextCompressor compressor;

  public AgenticLoop() {
    this(null);
  }

  /**
   * @param compressor optional context compressor applied before each model call;
   *                   {@code null} disables compression.
   */
  public AgenticLoop(ContextCompressor compressor) {
    this.compressor = compressor;
  }

  /** Final text plus every message appended beyond the input (for persistence). */
  public static class LoopResult {
    public final String finalText;
    public final List<LLMMessage> newMessages;

    public LoopResult(String finalText, List<LLMMessage> newMessages) {
      this.finalText = finalText;
      this.newMessages = newMessages;
    }
  }

  public LoopResult run(LLMClient client, List<LLMMessage> messages,
                        LLMToolRegistry tools, int maxIterations) throws LLMClientException {
    List<LLMMessage> appended = new ArrayList<>();
    String lastText = "";

    for (int i = 0; i < maxIterations; i++) {
      if (compressor != null) {
        messages = compressor.compress(messages);
      }
      LLMRequest request = new LLMRequest();
      request.setMessages(messages);
      request.setFormat(""); // disable structured json so tool calling works
      request.setTools(tools.schemas());

      LLMResponse response = client.call(request);
      lastText = response.getResponse() == null ? "" : response.getResponse();
      List<LLMToolCall> calls = response.getToolCalls();

      LLMMessage assistant = new LLMMessage("assistant", lastText);
      messages.add(assistant);
      appended.add(assistant);

      if (calls == null || calls.isEmpty()) {
        return new LoopResult(lastText, appended);
      }

      for (LLMToolCall call : calls) {
        String result = dispatch(tools, call);
        LLMMessage toolMsg = new LLMMessage("tool", result);
        toolMsg.setToolName(call.getName());
        toolMsg.setToolCallId(call.getId());
        messages.add(toolMsg);
        appended.add(toolMsg);
      }
    }
    return new LoopResult(lastText, appended);
  }

  private String dispatch(LLMToolRegistry tools, LLMToolCall call) {
    Map<String, String> args = new LinkedHashMap<>();
    if (call.getArguments() != null) {
      for (Map.Entry<String, Object> e : call.getArguments().entrySet()) {
        args.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
      }
    }
    try {
      return tools.dispatch(call.getName(), args);
    } catch (LLMToolException e) {
      return "Error: " + e.getMessage();
    }
  }
}

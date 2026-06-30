package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.base.LLMObjectMapper;
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

  /** Max consecutive no-tool-call turns we will nudge before giving up. */
  private static final int MAX_CONTINUATIONS = 3;

  private final ContextCompressor compressor;
  private final ContinuationPolicy continuationPolicy;

  public AgenticLoop() {
    this(null, null);
  }

  /**
   * @param compressor optional context compressor applied before each model call;
   *                   {@code null} disables compression.
   */
  public AgenticLoop(ContextCompressor compressor) {
    this(compressor, null);
  }

  /**
   * @param compressor         optional context compressor; {@code null} disables it.
   * @param continuationPolicy optional policy that, when the model emits no tool
   *                           call, decides whether to nudge and keep going;
   *                           {@code null} preserves the stop-on-first-no-tool behavior.
   */
  public AgenticLoop(ContextCompressor compressor, ContinuationPolicy continuationPolicy) {
    this.compressor = compressor;
    this.continuationPolicy = continuationPolicy;
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
    int continuationAttempts = 0;

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
        if (continuationPolicy != null && continuationAttempts < MAX_CONTINUATIONS
            && continuationPolicy.shouldContinue(lastText)) {
          continuationAttempts++;
          LLMMessage nudge = new LLMMessage("user",
              "Continue with the next pending step by calling the appropriate tool. "
                  + "If everything is complete, say so and stop.");
          messages.add(nudge);
          appended.add(nudge);
          continue;
        }
        return new LoopResult(lastText, appended);
      }

      // A tool was actually called: reset the no-tool nudge counter.
      continuationAttempts = 0;
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
        args.put(e.getKey(), stringifyArg(e.getValue()));
      }
    }
    try {
      return tools.dispatch(call.getName(), args);
    } catch (LLMToolException e) {
      return "Error: " + e.getMessage();
    }
  }

  /**
   * Flattens a tool-call argument to the {@code String} tools consume. Scalars
   * use their plain text form; objects and arrays are re-serialized to JSON so
   * tools that re-parse a structured argument (e.g. todo_write's {@code todos})
   * receive valid JSON rather than Java's {@code toString()} rendering.
   */
  private static String stringifyArg(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    try {
      return LLMObjectMapper.getObjectMapper().writeValueAsString(value);
    } catch (Exception e) {
      return value.toString();
    }
  }
}

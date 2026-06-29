package org.kendar.jllm.base;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LLMResponse {
  private String response;
  private String thinking;
  private boolean done;
  private List<LLMToolCall> toolCalls = new ArrayList<>();

  /**
   * The /api/chat endpoint nests the assistant reply under {@code message}.
   * Flatten it into {@link #response}/{@link #thinking} so existing callers keep working.
   */
  @JsonProperty("message")
  public void setMessage(Message message) {
    if (message == null) {
      return;
    }
    this.response = message.content;
    if (message.thinking != null) {
      this.thinking = message.thinking;
    }
    if (message.toolCalls != null) {
      for (ToolCall tc : message.toolCalls) {
        if (tc == null || tc.function == null) {
          continue;
        }
        LLMToolCall call = new LLMToolCall();
        call.setId(tc.id);
        call.setName(tc.function.name);
        if (tc.function.arguments != null) {
          call.setArguments(tc.function.arguments);
        }
        this.toolCalls.add(call);
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Message {
    public String role;
    public String content;
    public String thinking;
    @JsonProperty("tool_calls")
    public List<ToolCall> toolCalls;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ToolCall {
    public String id;
    public Function function;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Function {
    public String name;
    public Map<String, Object> arguments = new LinkedHashMap<>();
  }

  public List<LLMToolCall> getToolCalls() {
    return toolCalls;
  }

  public void setToolCalls(List<LLMToolCall> toolCalls) {
    this.toolCalls = toolCalls;
  }
  @JsonProperty("done_reason")
  private String doneReason;
  @JsonProperty("eval_count")
  private int evalCount;

  public String getResponse() {
    return response;
  }

  public void setResponse(String response) {
    this.response = response;
  }

  public String getThinking() {
    return thinking;
  }

  public void setThinking(String thinking) {
    this.thinking = thinking;
  }

  public boolean isDone() {
    return done;
  }

  public void setDone(boolean done) {
    this.done = done;
  }

  public String getDoneReason() {
    return doneReason;
  }

  public void setDoneReason(String doneReason) {
    this.doneReason = doneReason;
  }

  public int getEvalCount() {
    return evalCount;
  }

  public void setEvalCount(int evalCount) {
    this.evalCount = evalCount;
  }
}

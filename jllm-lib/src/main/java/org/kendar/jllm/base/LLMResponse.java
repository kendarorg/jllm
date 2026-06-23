package org.kendar.jllm.base;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LLMResponse {
  private String response;
  private String thinking;
  private boolean done;
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

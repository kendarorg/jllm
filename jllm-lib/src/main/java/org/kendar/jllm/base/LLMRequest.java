package org.kendar.jllm.base;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class LLMRequest {
  String model;

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public String getSystem() {
    return system;
  }

  public void setSystem(String system) {
    this.system = system;
  }

  String system;
  String prompt;
  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  boolean stream = false;

  public boolean isStream() {
    return stream;
  }

  public void setStream(boolean stream) {
    this.stream = stream;
  }

  String think;

  public String getThink() {
    return think;
  }

  public void setThink(String think) {
    this.think = think;
  }

  String keepAlive = "5m";

  public String getKeepAlive() {
    return keepAlive;
  }

  public void setKeepAlive(String keepAlive) {
    this.keepAlive = keepAlive;
  }



  public String getFormat() {
    return format;
  }

  public void setFormat(String format) {
    this.format = format;
  }

  public int getNumCtx() {
    return numCtx;
  }

  public void setNumCtx(int numCtx) {
    this.numCtx = numCtx;
  }

  public float getTemperature() {
    return temperature;
  }

  public void setTemperature(float temperature) {
    this.temperature = temperature;
  }

  public float getTopP() {
    return topP;
  }

  public void setTopP(float topP) {
    this.topP = topP;
  }

  public int getTopK() {
    return topK;
  }

  public void setTopK(int topK) {
    this.topK = topK;
  }

  String format="json";
  @JsonProperty("num_ctx")
  int numCtx=128*1000;
  /**
   * Creativity is a function of the temperature.
   */
  float temperature=0.4f;
  /**
   * Keeps tokens until 90% cumulative probability mass.
   * Prevents very low-probability (bug-prone) tokens.
   */
  @JsonProperty("top_p")
  float topP=0.9f;
  /**
   * Keeps the top K most likely tokens.
   */
  @JsonProperty("top_k")
  int topK=30;

  void forPlan(){
    temperature=0.4f;
    topP=0.92f;
    topK=70;
  }
  void forCoding(){
    temperature=0.2f;
    topP=0.87f;
    topK=30;
  }
  void forExploring(){
    temperature=0.83f;
    topP=0.85f;
    topK=20;
  }
  void forVerifying(){
    temperature=0.5f;
    topP=0.85f;
    topK=30;
  }
}

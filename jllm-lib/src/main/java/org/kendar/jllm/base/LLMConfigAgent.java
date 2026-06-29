package org.kendar.jllm.base;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.ArrayList;
import java.util.List;

@JacksonXmlRootElement(localName = "agent")
public class LLMConfigAgent implements LLMAgent {
  @JacksonXmlProperty(isAttribute = true)
  private String name;

  private String description;
  private String outputFormat;
  private String systemPrompt;
  private String model;
  /** Comma-separated list of tool names the agent may use (e.g. {@code <allowedTools>read_file,write_file</allowedTools>}). */
  private String allowedTools;

  @Override
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  @Override
  public String getOutputFormat() {
    return outputFormat;
  }

  public void setOutputFormat(String outputFormat) {
    this.outputFormat = outputFormat;
  }

  @Override
  public String getSystemPrompt() {
    return (systemPrompt == null || systemPrompt.isEmpty()) ? getDescription() : systemPrompt;
  }

  public void setSystemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
  }

  @Override
  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  @Override
  public List<String> getAllowedTools() {
    List<String> result = new ArrayList<>();
    if (allowedTools == null) {
      return result;
    }
    for (String t : allowedTools.split(",")) {
      String tt = t.trim();
      if (!tt.isEmpty()) {
        result.add(tt);
      }
    }
    return result;
  }

  public void setAllowedTools(String allowedTools) {
    this.allowedTools = allowedTools;
  }
}

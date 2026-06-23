package org.kendar.jllm;

import java.util.ArrayList;
import java.util.List;

public class LLMSettings {
  private String model;
  private String server;
  private List<String> settingDirs = new ArrayList<>();

  public List<String> getSettingDirs() {
    return settingDirs;
  }

  public void setSettingDirs(List<String> settingDirs) {
    this.settingDirs = settingDirs;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getServer() {
    return server;
  }

  public void setServer(String server) {
    this.server = server;
  }
}

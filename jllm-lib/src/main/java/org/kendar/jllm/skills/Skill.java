package org.kendar.jllm.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A skill loaded from a {@code SKILL.md} file. */
public class Skill {
  private String name;
  private String description;
  private Path path;
  private String body;
  private List<String> allowedTools = new ArrayList<>();

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Path getPath() {
    return path;
  }

  public void setPath(Path path) {
    this.path = path;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public List<String> getAllowedTools() {
    return allowedTools;
  }

  public void setAllowedTools(List<String> allowedTools) {
    this.allowedTools = allowedTools;
  }
}

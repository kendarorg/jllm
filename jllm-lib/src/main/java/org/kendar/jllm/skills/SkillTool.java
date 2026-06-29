package org.kendar.jllm.skills;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Loads the body of a named skill (and lists its sibling resource files). */
public class SkillTool implements LLMTool {

  private final SkillRegistry registry;

  public SkillTool(SkillRegistry registry) {
    this.registry = registry;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    return false;
  }

  @Override
  public String name() {
    return "skill";
  }

  @Override
  public String toolSchema() {
    try {
      ObjectNode root = LLMObjectMapper.getObjectMapper().createObjectNode();
      root.put("type", "function");
      ObjectNode function = root.putObject("function");
      function.put("name", name());
      function.put("description", "Load a skill by name, returning its instructions and resources.");
      ObjectNode parameters = function.putObject("parameters");
      parameters.put("type", "object");
      ObjectNode properties = parameters.putObject("properties");
      ObjectNode nameProp = properties.putObject("name");
      nameProp.put("type", "string");
      nameProp.put("description", "The name of the skill to load.");
      parameters.putArray("required").add("name");
      return LLMObjectMapper.getObjectMapper().writeValueAsString(root);
    } catch (Exception e) {
      throw new LLMToolException("Unable to serialize tool schema", e);
    }
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String name = args == null ? null : args.get("name");
    if (name == null || name.isEmpty()) {
      throw new LLMToolException("Missing required argument: name");
    }
    Skill skill = registry.get(name);
    if (skill == null) {
      return "Unknown skill: " + name + ". Available skills: " + availableNames();
    }

    StringBuilder sb = new StringBuilder();
    sb.append(skill.getBody() == null ? "" : skill.getBody());

    List<String> resources = siblingResources(skill.getPath());
    sb.append("\n\n# --- Skill resources ---\n");
    if (resources.isEmpty()) {
      sb.append("(no additional resource files)\n");
    } else {
      for (String r : resources) {
        sb.append("- ").append(r).append('\n');
      }
    }
    return sb.toString();
  }

  private String availableNames() {
    return registry.all().stream().map(Skill::getName).collect(Collectors.joining(", "));
  }

  private List<String> siblingResources(Path skillFile) {
    List<String> names = new ArrayList<>();
    if (skillFile == null || skillFile.getParent() == null) {
      return names;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(skillFile.getParent())) {
      for (Path f : files) {
        String fn = f.getFileName().toString();
        if (!fn.equals("SKILL.md")) {
          names.add(fn);
        }
      }
    } catch (IOException ignored) {
      // ignore unreadable folder
    }
    Collections.sort(names);
    return names;
  }
}

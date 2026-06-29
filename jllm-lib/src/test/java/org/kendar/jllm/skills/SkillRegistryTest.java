package org.kendar.jllm.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

  @Test
  void discoversParsesAndServesSkill(@TempDir Path wd) throws IOException {
    Path demo = wd.resolve(".jllm").resolve("skills").resolve("demo");
    Files.createDirectories(demo);
    String body = "This is the BODY of the demo skill.";
    Files.writeString(demo.resolve("SKILL.md"),
        "---\n"
            + "name: demo\n"
            + "description: A demo skill\n"
            + "allowed-tools: read_file, edit\n"
            + "---\n"
            + body + "\n");
    Files.writeString(demo.resolve("script.sh"), "#!/bin/sh\necho hi\n");

    SkillRegistry registry = new SkillRegistry();
    registry.discover(wd, null);

    Skill skill = registry.get("demo");
    assertNotNull(skill);
    assertEquals("demo", skill.getName());
    assertEquals("A demo skill", skill.getDescription());
    assertEquals(2, skill.getAllowedTools().size());
    assertTrue(skill.getAllowedTools().contains("read_file"));
    assertTrue(skill.getAllowedTools().contains("edit"));

    String catalog = registry.catalog();
    assertTrue(catalog.contains("demo: A demo skill"));
    assertFalse(catalog.contains("BODY"), "catalog must not contain the body");

    SkillTool tool = new SkillTool(registry);
    String result = tool.act(Map.of("name", "demo"));
    assertTrue(result.contains(body));
    assertTrue(result.contains("script.sh"));

    String missing = tool.act(Map.of("name", "nope"));
    assertTrue(missing.toLowerCase().contains("unknown"));
    assertTrue(missing.contains("demo"));
  }

  @Test
  void noFrontmatterDefaultsToFolderName(@TempDir Path wd) throws IOException {
    Path folder = wd.resolve(".jllm").resolve("skills").resolve("plain");
    Files.createDirectories(folder);
    String content = "Just some content, no frontmatter at all.";
    Files.writeString(folder.resolve("SKILL.md"), content);

    SkillRegistry registry = new SkillRegistry();
    registry.discover(wd, null);

    Skill skill = registry.get("plain");
    assertNotNull(skill);
    assertEquals("plain", skill.getName());
    assertEquals(content, skill.getBody());
  }
}

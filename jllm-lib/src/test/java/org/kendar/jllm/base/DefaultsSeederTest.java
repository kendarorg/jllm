package org.kendar.jllm.base;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.jllm.LLMSettings;
import org.kendar.jllm.skills.SkillRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultsSeederTest {

  @Test
  void seedCopiesBundledDefaults(@TempDir Path dir) {
    DefaultsSeeder.seed(dir.toFile());

    assertTrue(new File(dir.toFile(), ".jllm/agents/default/code-reviewer.xml").exists());
    assertTrue(new File(dir.toFile(), ".jllm/skills/commit-helper/SKILL.md").exists());
    assertTrue(new File(dir.toFile(), ".jllm/skills/new-java-class/templates/Class.java.tmpl").exists());
    assertTrue(new File(dir.toFile(), ".jllm/JLLM.md").exists());
  }

  @Test
  void seedDoesNotOverwriteExisting(@TempDir Path dir) throws Exception {
    Path jllmMd = dir.resolve(".jllm").resolve("JLLM.md");
    Files.createDirectories(jllmMd.getParent());
    Files.writeString(jllmMd, "MY CONTENT");

    DefaultsSeeder.seed(dir.toFile());

    assertEquals("MY CONTENT", Files.readString(jllmMd));
  }

  @Test
  void seedIsIdempotent(@TempDir Path dir) {
    DefaultsSeeder.seed(dir.toFile());
    assertDoesNotThrow(() -> DefaultsSeeder.seed(dir.toFile()));
    assertTrue(new File(dir.toFile(), ".jllm/JLLM.md").exists());
  }

  @Test
  void seededAgentsLoadViaConfigManager(@TempDir Path dir) {
    DefaultsSeeder.seed(dir.toFile());

    LLMSettings settings = new LLMSettings();
    settings.setSettingDirs(List.of(dir.toString()));
    LLMConfigManager.initialize(settings);

    LLMAgent codeReviewer = LLMConfigManager.getAgent("code-reviewer");
    assertNotNull(codeReviewer);
    assertEquals("code-reviewer", codeReviewer.getName());

    LLMAgent testingExpert = LLMConfigManager.getAgent("testing-expert");
    assertNotNull(testingExpert);
    assertEquals("testing-expert", testingExpert.getName());
  }

  @Test
  void seededSkillsDiscoverable(@TempDir Path dir) {
    DefaultsSeeder.seed(dir.toFile());

    SkillRegistry registry = new SkillRegistry();
    registry.discover(dir, null);

    assertNotNull(registry.get("commit-helper"));
    assertTrue(registry.catalog().contains("commit-helper"));
  }
}

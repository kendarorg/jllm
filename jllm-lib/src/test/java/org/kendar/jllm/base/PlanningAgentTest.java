package org.kendar.jllm.base;

import org.junit.jupiter.api.Test;
import org.kendar.jllm.LLMSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanningAgentTest {

  /**
   * Walk up from the test working dir until the repository root that holds the
   * real {@code .jllm} directory is found, so the test works whether maven runs
   * from the module dir or the reactor root.
   */
  private static Path settingDirWithDefaultJllm() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null) {
      for (Path candidate : new Path[]{dir, dir.resolve("default")}) {
        if (Files.isDirectory(candidate.resolve(".jllm").resolve("agents").resolve("default"))) {
          return candidate;
        }
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("No .jllm/agents/default found above " + System.getProperty("user.dir"));
  }

  @Test
  void loadsPlanningAgentFromDefault() {
    Path settingDir = settingDirWithDefaultJllm();

    LLMSettings settings = new LLMSettings();
    settings.setSettingDirs(List.of(settingDir.toString()));

    LLMConfigManager.initialize(settings);

    LLMAgent planning = LLMConfigManager.getAgent("planning");

    assertNotNull(planning);
    assertEquals("planning", planning.getName());

    String description = planning.getDescription().trim();
    assertTrue(description.startsWith("Break the request down into an ordered, actionable todo list."),
        "unexpected description: " + description);

    String outputFormat = planning.getOutputFormat().trim();
    assertTrue(outputFormat.startsWith("A todo list"), "unexpected outputFormat: " + outputFormat);
    assertTrue(outputFormat.contains("[] "), "outputFormat should describe '[] ' todo items");
  }
}

package org.kendar.jllm.base;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.jllm.LLMSettings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LLMConfigManagerTest {

  private static final String SCOPE_CLASSIFIER_JSON =
      "{\n" +
      "  \"name\": \"scopeClassifier\",\n" +
      "  \"description\": \"Classify the scope of the prompt\",\n" +
      "  \"classification\": {\n" +
      "    \"plan\": \"Create a plan for the request\",\n" +
      "    \"code\": \"Generate code for the request\",\n" +
      "    \"oneShot\": \"Simple request\",\n" +
      "    \"research\": \"Research the request\"\n" +
      "  },\n" +
      "  \"retries\": 3\n" +
      "}";

  /**
   * Walk up from the test working dir until the repository root that holds the
   * real {@code .jllm} directory is found, so the test works whether maven runs
   * from the module dir or the reactor root.
   */
  private static Path settingDirWithDefaultJllm() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null) {
      // The setting dir is the one holding .jllm/classifiers/default — check the
      // dir itself and a "default" sub-dir at each level walking up to the root.
      for (Path candidate : new Path[]{dir, dir.resolve("default")}) {
        if (Files.isDirectory(candidate.resolve(".jllm").resolve("classifiers").resolve("default"))) {
          return candidate;
        }
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("No .jllm/classifiers/default found above " + System.getProperty("user.dir"));
  }

  @Test
  void loadsDefaultScopeClassifierFromRepoJllm() {
    Path settingDir = settingDirWithDefaultJllm();

    LLMSettings settings = new LLMSettings();
    settings.setSettingDirs(List.of(settingDir.toString()));

    LLMConfigManager.initialize(settings);

    LLMConfigClassifier scope =
        LLMConfigManager.get("scopeClassifier", LLMConfigClassifier.class);

    assertNotNull(scope);
    assertEquals("scopeClassifier", scope.getName());
    assertEquals("Classify the scope of the prompt", scope.getDescription());
    assertEquals(3, scope.getRetries());
    assertEquals(
        java.util.Set.of("plan", "code", "oneShot", "research"),
        scope.getClassification().keySet());
    assertEquals("Simple request", scope.getClassification().get("oneShot"));
  }

  @Test
  void initializeCreatesJllmDirWhenMissing(@TempDir Path dir) {
    LLMSettings settings = new LLMSettings();
    settings.setSettingDirs(List.of(dir.toString()));

    LLMConfigManager.initialize(settings);

    assertTrue(new File(dir.toFile(), ".jllm").isDirectory());
  }

  @Test
  void initializeLoadsClassifierRecursively(@TempDir Path dir) throws Exception {
    // Mirror the real layout: .jllm/classifiers/default/scopeClassifier.json
    Path classifiers = dir.resolve(".jllm").resolve("classifiers").resolve("default");
    Files.createDirectories(classifiers);
    Files.writeString(classifiers.resolve("scopeClassifier.json"), SCOPE_CLASSIFIER_JSON);

    LLMSettings settings = new LLMSettings();
    settings.setSettingDirs(List.of(dir.toString()));

    LLMConfigManager.initialize(settings);

    LLMConfigClassifier loaded =
        LLMConfigManager.get("scopeClassifier", LLMConfigClassifier.class);

    assertNotNull(loaded);
    assertEquals("scopeClassifier", loaded.getName());
    assertEquals("Classify the scope of the prompt", loaded.getDescription());
    assertEquals(3, loaded.getRetries());
    assertEquals(4, loaded.getClassification().size());
    assertEquals("Create a plan for the request", loaded.getClassification().get("plan"));
  }
}

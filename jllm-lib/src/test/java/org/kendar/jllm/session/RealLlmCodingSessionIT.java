package org.kendar.jllm.session;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.jllm.LLMSettings;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.chat.H2ChatStore;
import org.kendar.jllm.ollama.OllamaClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Manual end-to-end tests against a running Ollama instance. All disabled in CI.
 * Run with a live server: set JLLM_MODEL / JLLM_SERVER as desired.
 */
class RealLlmCodingSessionIT {

  private static OllamaClient realClient() {
    LLMSettings settings = new LLMSettings();
    String model = System.getenv().getOrDefault("JLLM_MODEL", "qwen2.5-coder");
    String server = System.getenv().getOrDefault("JLLM_SERVER", "http://localhost:11434");
    settings.setModel(model);
    settings.setServer(server);
    return new OllamaClient(settings);
  }

  private static Path repoDefaultDir() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null) {
      for (Path candidate : new Path[]{dir, dir.resolve("default")}) {
        if (Files.isDirectory(candidate.resolve(".jllm").resolve("agents"))) {
          return candidate;
        }
      }
      dir = dir.getParent();
    }
    return Path.of(System.getProperty("user.dir"));
  }

  @Test
  @Disabled("requires a running Ollama; run manually")
  void createAndReadFileEndToEnd(@TempDir Path workingDir) throws Exception {
    H2ChatStore store = H2ChatStore.inMemory();
    CodingSession session = CodingSessionFactory.create(
        workingDir, realClient(), store, "e2e", "code");

    String reply = session.send(
        "create a file hello.txt containing the text hi, then read it back and tell me its contents");

    System.out.println("Assistant: " + reply);
    assertTrue(Files.exists(workingDir.resolve("hello.txt")));
  }

  @Test
  @Disabled("requires a running Ollama; run manually")
  void delegateToSubAgent(@TempDir Path workingDir) throws Exception {
    LLMSettings settings = new LLMSettings();
    settings.setSettingDirs(List.of(repoDefaultDir().toString()));
    LLMConfigManager.initialize(settings);

    H2ChatStore store = H2ChatStore.inMemory();
    CodingSession session = CodingSessionFactory.create(
        workingDir, realClient(), store, "delegate", "code");

    String reply = session.send(
        "Delegate to the code sub-agent the task of creating a file note.txt with the word done.");
    System.out.println("Assistant: " + reply);
  }

  @Test
  @Disabled("requires a running Ollama; run manually")
  void webAndSkillSmoke(@TempDir Path workingDir) throws Exception {
    H2ChatStore store = H2ChatStore.inMemory();
    CodingSession session = CodingSessionFactory.create(
        workingDir, realClient(), store, "smoke", "code");

    String reply = session.send(
        "Fetch https://example.com and summarize the first line, or list available skills.");
    System.out.println("Assistant: " + reply);
  }
}

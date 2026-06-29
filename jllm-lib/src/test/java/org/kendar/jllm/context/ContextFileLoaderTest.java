package org.kendar.jllm.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContextFileLoaderTest {

  @Test
  void concatenatesNearestLast(@TempDir Path root) throws IOException {
    Path b = root.resolve("a").resolve("b");
    Files.createDirectories(b);
    Files.writeString(root.resolve("JLLM.md"), "ROOT_CONTENT");
    Files.writeString(b.resolve("JLLM.md"), "NEAREST_CONTENT");

    String loaded = new ContextFileLoader(b, null).load();

    assertTrue(loaded.contains("ROOT_CONTENT"));
    assertTrue(loaded.contains("NEAREST_CONTENT"));
    assertTrue(loaded.indexOf("ROOT_CONTENT") < loaded.indexOf("NEAREST_CONTENT"),
        "nearest file must appear after the root file");
  }

  @Test
  void picksUpQwenAlias(@TempDir Path root) throws IOException {
    Files.writeString(root.resolve("QWEN.md"), "QWEN_CONTENT");
    String loaded = new ContextFileLoader(root, null).load();
    assertTrue(loaded.contains("QWEN_CONTENT"));
  }

  @Test
  void emptyWhenNoFiles(@TempDir Path root) {
    assertEquals("", new ContextFileLoader(root, null).load());
  }

  @Test
  void memoryFileTargetsWorkingDirJllm(@TempDir Path root) {
    assertEquals(root.resolve("JLLM.md"), new ContextFileLoader(root, null).memoryFile());
  }
}

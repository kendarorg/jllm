package org.kendar.jllm.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SaveMemoryToolTest {

  @Test
  void createsThenAppendsUnderSameSection(@TempDir Path wd) throws IOException {
    Path memory = wd.resolve("JLLM.md");
    SaveMemoryTool tool = new SaveMemoryTool(memory);

    tool.act(Map.of("fact", "first fact"));
    String afterFirst = Files.readString(memory);
    assertTrue(afterFirst.contains("## Added Memories"));
    assertTrue(afterFirst.contains("- first fact"));

    tool.act(Map.of("fact", "second fact"));
    String afterSecond = Files.readString(memory);
    assertTrue(afterSecond.contains("- first fact"));
    assertTrue(afterSecond.contains("- second fact"));

    int firstHeader = afterSecond.indexOf("## Added Memories");
    int lastHeader = afterSecond.lastIndexOf("## Added Memories");
    assertEquals(firstHeader, lastHeader, "only one section header expected");
  }

  @Test
  void requiresApprovalAndAvailable(@TempDir Path wd) {
    SaveMemoryTool tool = new SaveMemoryTool(wd.resolve("JLLM.md"));
    assertTrue(tool.available());
    assertTrue(tool.requiresApproval());
    assertEquals("save_memory", tool.name());
  }
}

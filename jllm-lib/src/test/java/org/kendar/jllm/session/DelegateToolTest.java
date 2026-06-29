package org.kendar.jllm.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.jllm.base.LLMConfigAgent;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.tools.DelegateTool;
import org.kendar.jllm.tools.LLMToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DelegateToolTest {

  private static LLMConfigAgent registerWriter() {
    LLMConfigAgent writer = new LLMConfigAgent();
    writer.setName("writer");
    writer.setSystemPrompt("You are a file writer.");
    writer.setAllowedTools("write_file");
    LLMConfigManager.register(writer);
    return writer;
  }

  @Test
  void delegatesAndReturnsOnlyTheSummary(@TempDir Path workingDir) throws Exception {
    registerWriter();
    LLMToolRegistry parentRegistry = LLMToolRegistry.withDefaults(workingDir);

    StubClient client = new StubClient()
        .toolCall("write_file", Map.of("path", "f.txt", "content", "x"))
        .text("created");

    DelegateTool delegate = new DelegateTool(client, workingDir, parentRegistry, 2, 0, 10);
    parentRegistry.register(delegate);

    String result = delegate.act(Map.of("agent", "writer", "task", "make a file"));

    assertEquals("created", result);
    assertTrue(Files.exists(workingDir.resolve("f.txt")));
  }

  @Test
  void allowlistFiltersTools(@TempDir Path workingDir) {
    LLMToolRegistry registry = LLMToolRegistry.withDefaults(workingDir);
    LLMToolRegistry filtered = registry.filtered(List.of("write_file"));
    assertNotNull(filtered.get("write_file"));
    assertNull(filtered.get("read_file"));
  }

  @Test
  void depthCapRefusesWithoutCallingClient(@TempDir Path workingDir) throws Exception {
    registerWriter();
    LLMToolRegistry parentRegistry = LLMToolRegistry.withDefaults(workingDir);
    StubClient client = new StubClient().text("should not be called");

    // depth == maxDepth -> must refuse before touching the client.
    DelegateTool delegate = new DelegateTool(client, workingDir, parentRegistry, 2, 2, 10);

    String result = delegate.act(Map.of("agent", "writer", "task", "make a file"));

    assertTrue(result.contains("refused"), result);
    assertEquals(0, client.calls.get());
  }
}

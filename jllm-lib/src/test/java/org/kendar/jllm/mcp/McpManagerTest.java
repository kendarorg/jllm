package org.kendar.jllm.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.jllm.tools.LLMToolRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpManagerTest {

  @Test
  void parsesMcpServersConfig() {
    String json = "{\"mcpServers\":{"
        + "\"files\":{\"command\":\"npx\",\"args\":[\"-y\",\"server\"],\"env\":{\"K\":\"V\"}},"
        + "\"off\":{\"command\":\"x\",\"enabled\":false}}}";

    List<McpServerConfig> servers = McpManager.parse(json);

    assertEquals(2, servers.size());
    McpServerConfig files = servers.stream().filter(s -> s.getName().equals("files")).findFirst().orElseThrow();
    assertEquals("npx", files.getCommand());
    assertEquals(List.of("-y", "server"), files.getArgs());
    assertEquals("V", files.getEnv().get("K"));
    assertTrue(files.isEnabled());

    McpServerConfig off = servers.stream().filter(s -> s.getName().equals("off")).findFirst().orElseThrow();
    assertFalse(off.isEnabled());
  }

  @Test
  void noConfigFileYieldsEmptyManager(@TempDir Path workingDir) {
    LLMToolRegistry registry = LLMToolRegistry.withDefaults(workingDir);
    try (McpManager manager = McpManager.loadAndRegister(workingDir, registry)) {
      assertEquals(0, manager.serverCount());
    }
  }

  @Test
  void emptyJsonYieldsNoServers() {
    assertTrue(McpManager.parse("{}").isEmpty());
  }
}

package org.kendar.jllm.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellToolTest {

  @TempDir
  Path root;

  @Test
  void echoHello() {
    ShellTool shell = new ShellTool(root);
    String res = shell.act(Map.of("command", "echo hello"));
    assertTrue(res.contains("hello"), res);
    assertTrue(res.contains("exit code: 0"), res);
    assertTrue(shell.requiresApproval());
  }
}

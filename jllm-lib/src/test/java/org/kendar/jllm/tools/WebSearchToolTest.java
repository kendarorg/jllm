package org.kendar.jllm.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

  @Test
  void notConfiguredReturnsMessage() {
    WebSearchTool tool = new WebSearchTool(null);
    String res = tool.act(Map.of("query", "anything"));
    assertTrue(res.contains("not configured"), res);
  }
}

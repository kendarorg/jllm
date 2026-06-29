package org.kendar.jllm.tools;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

  @Test
  @Disabled("requires network: run manually to verify real HTTP fetching against https://example.com")
  void fetchesExampleCom() {
    WebFetchTool tool = new WebFetchTool();
    String res = tool.act(Map.of("url", "https://example.com"));
    assertTrue(res.toLowerCase().contains("example"));
  }
}

package org.kendar.jllm.session;

import org.junit.jupiter.api.Test;
import org.kendar.jllm.base.LLMMessage;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.tools.LLMToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgenticLoopTest {

  /** A trivial tool that records invocations and returns a fixed result. */
  private static class FakeTool implements LLMTool {
    int invocations = 0;

    @Override public boolean available() { return true; }
    @Override public boolean requiresApproval() { return false; }
    @Override public String name() { return "ping"; }
    @Override public String toolSchema() {
      return "{\"type\":\"function\",\"function\":{\"name\":\"ping\","
          + "\"description\":\"ping\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}";
    }
    @Override public String act(Map<String, String> args) { invocations++; return "pong"; }
  }

  @Test
  void dispatchesToolThenReturnsFinalAnswer() throws Exception {
    FakeTool tool = new FakeTool();
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(tool);

    StubClient client = new StubClient()
        .toolCall("ping", Map.of())
        .text("all done");

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("system", "sys"));
    messages.add(new LLMMessage("user", "go"));

    AgenticLoop.LoopResult result = new AgenticLoop().run(client, messages, registry, 10);

    assertEquals("all done", result.finalText);
    assertEquals(1, tool.invocations);
    // appended: assistant(toolcall), tool(result), assistant(final)
    assertEquals(3, result.newMessages.size());
    assertEquals("tool", result.newMessages.get(1).getRole());
    assertEquals("pong", result.newMessages.get(1).getContent());
    assertEquals("ping", result.newMessages.get(1).getToolName());
  }

  /** Captures the stringified value of its 'todos' argument. */
  private static class CapturingTool implements LLMTool {
    String captured;

    @Override public boolean available() { return true; }
    @Override public boolean requiresApproval() { return false; }
    @Override public String name() { return "todo_write"; }
    @Override public String toolSchema() {
      return "{\"type\":\"function\",\"function\":{\"name\":\"todo_write\","
          + "\"description\":\"d\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}";
    }
    @Override public String act(Map<String, String> args) { captured = args.get("todos"); return "ok"; }
  }

  @Test
  void serializesNestedArgumentsAsJson() throws Exception {
    CapturingTool tool = new CapturingTool();
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(tool);

    // The model sends 'todos' as an actual JSON array (parsed to a List), not a string.
    Object todos = List.of(
        Map.of("content", "Read config", "status", "completed"),
        Map.of("content", "Add field", "status", "in_progress"));
    StubClient client = new StubClient()
        .toolCall("todo_write", Map.of("todos", todos))
        .text("done");

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("user", "go"));

    new AgenticLoop().run(client, messages, registry, 10);

    // Must be valid JSON the tool can re-parse, not Java's [{content=...}] toString.
    assertNotNull(tool.captured);
    assertFalse(tool.captured.contains("content="), "should not be Java toString form");
    List<?> reparsed = org.kendar.jllm.base.LLMObjectMapper.getObjectMapper()
        .readValue(tool.captured, List.class);
    assertEquals(2, reparsed.size());
  }

  @Test
  void stopsAtMaxIterationsWithoutInfiniteLoop() throws Exception {
    FakeTool tool = new FakeTool();
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(tool);

    // always returns a tool call -> would loop forever without the cap.
    StubClient client = new StubClient().toolCall("ping", Map.of());

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("system", "sys"));

    AgenticLoop.LoopResult result = new AgenticLoop().run(client, messages, registry, 3);

    assertEquals(3, client.calls.get());
    assertNotNull(result.finalText);
  }
}

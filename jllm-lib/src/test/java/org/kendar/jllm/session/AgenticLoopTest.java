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
  void resumesWhenPolicySaysContinue() throws Exception {
    FakeTool tool = new FakeTool();
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(tool);

    // First turn narrates without a tool call; the policy nudges once, then the
    // model fires the tool and finally answers.
    StubClient client = new StubClient()
        .text("Now let me create the model class:")
        .toolCall("ping", Map.of())
        .text("all done");

    // Continue exactly once, then stop.
    int[] allowed = {1};
    ContinuationPolicy policy = last -> allowed[0]-- > 0;

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("user", "go"));

    AgenticLoop.LoopResult result =
        new AgenticLoop(null, policy).run(client, messages, registry, 10);

    assertEquals("all done", result.finalText);
    assertEquals(1, tool.invocations);
    // A nudge 'user' message was injected after the first no-tool turn.
    assertTrue(result.newMessages.stream()
            .anyMatch(m -> "user".equals(m.getRole()) && m.getContent().contains("next pending step")),
        "expected a continuation nudge message");
  }

  @Test
  void stopsAtContinuationCapWithoutInfiniteLoop() throws Exception {
    LLMToolRegistry registry = new LLMToolRegistry();

    // Model never calls a tool; policy always wants to continue.
    StubClient client = new StubClient().text("still thinking, let me continue");
    ContinuationPolicy policy = last -> true;

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("user", "go"));

    AgenticLoop.LoopResult result =
        new AgenticLoop(null, policy).run(client, messages, registry, 50);

    // Bounded by MAX_CONTINUATIONS (3 nudges) -> 4 model calls, NOT maxIterations.
    assertEquals(4, client.calls.get());
    assertNotNull(result.finalText);
  }

  @Test
  void noPolicyReturnsImmediatelyOnNoToolCall() throws Exception {
    LLMToolRegistry registry = new LLMToolRegistry();
    StubClient client = new StubClient().text("done").text("should not be reached");

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(new LLMMessage("user", "go"));

    AgenticLoop.LoopResult result = new AgenticLoop().run(client, messages, registry, 10);

    assertEquals("done", result.finalText);
    assertEquals(1, client.calls.get());
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

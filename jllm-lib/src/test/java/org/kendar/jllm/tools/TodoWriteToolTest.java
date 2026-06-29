package org.kendar.jllm.tools;

import org.junit.jupiter.api.Test;
import org.kendar.jllm.exceptions.LLMToolException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

  @Test
  void storesAndRendersTheList() {
    TodoStore store = new TodoStore();
    TodoWriteTool tool = new TodoWriteTool(store);

    String result = tool.act(Map.of("todos",
        "[{\"content\":\"Read config\",\"status\":\"completed\"},"
            + "{\"content\":\"Add field\",\"status\":\"in_progress\"}]"));

    assertTrue(result.contains("[x] Read config"), result);
    assertTrue(result.contains("[~] Add field"), result);
    assertEquals(2, store.items().size());
  }

  @Test
  void rejectsInvalidStatus() {
    TodoWriteTool tool = new TodoWriteTool(new TodoStore());
    assertThrows(LLMToolException.class, () -> tool.act(Map.of("todos",
        "[{\"content\":\"x\",\"status\":\"bogus\"}]")));
  }

  @Test
  void rejectsMissingArgument() {
    TodoWriteTool tool = new TodoWriteTool(new TodoStore());
    assertThrows(LLMToolException.class, () -> tool.act(Map.of()));
  }

  @Test
  void replaceOverwritesPreviousList() {
    TodoStore store = new TodoStore();
    TodoWriteTool tool = new TodoWriteTool(store);
    tool.act(Map.of("todos", "[{\"content\":\"a\",\"status\":\"pending\"}]"));
    tool.act(Map.of("todos", "[{\"content\":\"b\",\"status\":\"pending\"}]"));
    assertEquals(1, store.items().size());
    assertEquals("b", store.items().get(0).getContent());
  }
}

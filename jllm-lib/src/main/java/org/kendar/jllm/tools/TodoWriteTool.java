package org.kendar.jllm.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.util.List;
import java.util.Map;

/**
 * Lets the model record and update a structured todo list for the current task.
 * The model always sends the complete, updated list as a JSON array; the tool
 * stores it in the shared {@link TodoStore} and echoes the rendered list back.
 */
public class TodoWriteTool implements LLMTool {

  private final TodoStore store;

  public TodoWriteTool(TodoStore store) {
    if (store == null) {
      throw new LLMToolException("TodoStore must not be null");
    }
    this.store = store;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    return false;
  }

  @Override
  public String name() {
    return "todo_write";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder(name(),
            "Record and update the task plan as a todo list. Always send the COMPLETE, "
                + "updated list. Use this to plan multi-step work and track progress: mark "
                + "exactly one item 'in_progress' while working on it, then 'completed'.")
        .prop("todos", "string",
            "A JSON array of objects, each with 'content' (string) and 'status' "
                + "(one of: pending, in_progress, completed). Example: "
                + "[{\"content\":\"Read config\",\"status\":\"completed\"},"
                + "{\"content\":\"Add field\",\"status\":\"in_progress\"}]",
            true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String todos = args == null ? null : args.get("todos");
    if (todos == null || todos.isBlank()) {
      throw new LLMToolException("Missing required argument: todos");
    }
    List<TodoStore.Item> items;
    try {
      items = LLMObjectMapper.getObjectMapper().readValue(
          todos, new TypeReference<List<TodoStore.Item>>() {
          });
    } catch (Exception e) {
      throw new LLMToolException("todos must be a JSON array of {content,status}: " + e.getMessage(), e);
    }
    for (TodoStore.Item item : items) {
      if (item.getContent() == null || item.getContent().isBlank()) {
        throw new LLMToolException("Each todo needs a non-empty 'content'");
      }
      String status = item.getStatus() == null ? "pending" : item.getStatus().toLowerCase();
      if (!status.equals("pending") && !status.equals("in_progress") && !status.equals("completed")) {
        throw new LLMToolException("Invalid status '" + item.getStatus()
            + "' (use pending, in_progress or completed)");
      }
      item.setStatus(status);
    }
    store.replace(items);
    return "Todo list updated:\n" + store.render();
  }
}

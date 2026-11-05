package org.kendar.llm.harness.context.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.List;

/** {@code todo_write} and {@code todo_read}: the agent's own task list. */
final class TodoTools {

    private TodoTools() {
    }

    static String key(org.kendar.llm.harness.tools.ToolContext ctx) {
        return ctx.run() == null ? null : ctx.run().contextKey();
    }

    static List<HarnessTool> create(TodoStore store, ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.putObject("id").put("type", "string").put("description", "Short stable id; defaults to the position");
        props.putObject("content").put("type", "string").put("description", "What has to be done");
        props.putObject("status").put("type", "string").putArray("enum").add("pending").add("in_progress")
                .add("completed");
        item.putArray("required").add("content").add("status");
        ObjectNode todos = schema.putObject("properties").putObject("todos");
        todos.put("type", "array").put("description", "The complete list; it replaces the previous one");
        todos.set("items", item);
        schema.putArray("required").add("todos");

        HarnessTool write = new SimpleTool("todo_write",
                "Writes the task list of a multi-step job (3 or more steps). Send the complete list every time; it "
                        + "replaces the previous one. Keep at most one item in_progress and mark items completed as "
                        + "soon as they are done. The current list is always shown to you with the request.",
                schema, "Todo", ToolKind.READ_ONLY, (ctx, in) -> {
            ArrayNode saved = store.replace(ctx.chatId(), key(ctx), in.path("todos"));
            if (ctx.run() != null) {
                ObjectNode ev = mapper.createObjectNode();
                ev.set("todos", saved);
                ctx.run().sink().emit("todos", ev);
            }
            return "Todo list updated (" + saved.size() + " items). Continue with the task.";
        });
        HarnessTool read = new SimpleTool("todo_read", "Returns the current task list.",
                SimpleTool.schema(), "Todo", ToolKind.READ_ONLY, (ctx, in) -> {
            String text = TodoStore.render(store.read(ctx.chatId(), key(ctx)));
            return text == null ? "The todo list is empty." : text;
        });
        return List.of(write, read);
    }
}

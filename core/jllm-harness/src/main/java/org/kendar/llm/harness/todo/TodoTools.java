package org.kendar.llm.harness.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.List;

/**
 * {@code todo_write} and {@code todo_read}: the agent's own task list, saved per chat and agent in a
 * {@link TodoStore}. Offered with any context strategy when {@code context.todos} is on; the scope strategy always
 * offers them and also shows the list in the request.
 */
public final class TodoTools {

    public static final String WRITE = "todo_write";
    public static final String READ = "todo_read";

    private TodoTools() {
    }

    static String key(ToolContext ctx) {
        return ctx.run() == null ? null : ctx.run().contextKey();
    }

    /** The system prompt section for a strategy that does not show the list in the request. */
    public static String systemAddendum() {
        return """
            # Task list

            For work with 3 or more steps, or when the user asks you to track the work, write a task list \
            with todo_write before the first step and keep it current (send the complete list each time, at \
            most one item in_progress, mark items completed as soon as they are done). The list is saved with \
            the chat and survives context compaction: read it with todo_read when you resume the work.""";
    }

    /**
     * @param shown the strategy shows the current list with every request (scope): the description says so
     */
    public static List<HarnessTool> create(TodoStore store, ObjectMapper mapper, boolean shown) {
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

        HarnessTool write = new SimpleTool(WRITE,
                "Writes the task list of a multi-step job (3 or more steps). Send the complete list every time; it "
                        + "replaces the previous one. Keep at most one item in_progress and mark items completed as "
                        + "soon as they are done. "
                        + (shown ? "The current list is always shown to you with the request."
                        : "todo_read returns the current list."),
                schema, "Todo", ToolKind.READ_ONLY, (ctx, in) -> {
            ArrayNode saved = store.replace(ctx.chatId(), key(ctx), in.path("todos"));
            if (ctx.run() != null) {
                ObjectNode ev = mapper.createObjectNode();
                ev.set("todos", saved);
                ctx.run().sink().emit("todos", ev);
            }
            return "Todo list updated (" + saved.size() + " items). Continue with the task.";
        });
        HarnessTool read = new SimpleTool(READ, "Returns the current task list.",
                SimpleTool.schema(), "Todo", ToolKind.READ_ONLY, (ctx, in) -> {
            String text = TodoStore.render(store.read(ctx.chatId(), key(ctx)));
            return text == null ? "The todo list is empty." : text;
        });
        return List.of(write, read);
    }
}

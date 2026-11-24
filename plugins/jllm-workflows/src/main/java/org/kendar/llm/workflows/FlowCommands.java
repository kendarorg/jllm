package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.workflows.WorkflowApi;

import org.kendar.llm.harness.commands.CustomCommand.ChatLine;

import java.util.List;
import java.util.Set;

/** The chat slash commands over {@link WorkflowApi}. Output is plain text for the chat. */
final class FlowCommands {
    private final WorkflowApi api;
    private final String user;
    private final Set<String> roles;

    FlowCommands(WorkflowApi api, String user, Set<String> roles) {
        this.api = api;
        this.user = user;
        this.roles = roles;
    }

    String list(String chatId, String arg, List<ChatLine> history) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode f : api.list(user, roles)) {
            sb.append(f.path("scope").asText()).append('/').append(f.path("name").asText()).append('\n');
        }
        return sb.isEmpty() ? "No flows." : sb.toString().stripTrailing();
    }

    String start(String chatId, String arg, List<ChatLine> history) {
        boolean withChat = false;
        String a = arg == null ? "" : arg.trim();
        if (a.equals("--chat") || a.startsWith("--chat ")) { // the conversation is optional input, copied in
            withChat = true;
            a = a.substring(6).trim();
        } else if (a.contains(" --chat ") || a.endsWith(" --chat")) {
            withChat = true;
            a = (a.replace(" --chat ", " ").replaceAll(" --chat$", "")).trim();
        }
        String[] p = split(a, 2);
        if (p[0].isEmpty()) return "Usage: /flow-start [--chat] NAME [JSON input]";
        String name = p[0];
        String scope = null;
        int slash = name.indexOf('/');
        if (slash > 0) { // scope/name states the scope
            scope = name.substring(0, slash);
            name = name.substring(slash + 1);
        }
        JsonNode in = p[1].isEmpty() ? Json.M.createObjectNode() : Json.parse(p[1]);
        JsonNode chat = null;
        if (withChat) {
            var arr = Json.M.createArrayNode();
            for (ChatLine l : history) arr.addObject().put("role", l.role()).put("text", l.text());
            chat = arr;
        }
        return show(api.start(user, roles, name, scope, in, chat));
    }

    String resume(String chatId, String arg, List<ChatLine> history) {
        String[] p = split(arg, 2);
        if (p[0].isEmpty() || p[1].isEmpty()) return "Usage: /flow-resume RUN ANSWER (a JSON list for several picks)";
        String a = p[1];
        JsonNode answer = a.startsWith("[") ? Json.parse(a) : Json.M.getNodeFactory().textNode(a);
        return show(api.answer(user, roles, p[0], answer));
    }

    String request(String chatId, String arg, List<ChatLine> history) {
        String[] p = split(arg, 3);
        if (p[0].isEmpty() || p[1].isEmpty()) return "Usage: /flow-request RUN KIND [JSON]";
        JsonNode payload = p[2].isEmpty() ? Json.M.nullNode() : tryJson(p[2]);
        return show(api.request(user, roles, p[0], p[1], payload));
    }

    String cancel(String chatId, String arg, List<ChatLine> history) {
        String id = arg == null ? "" : arg.trim();
        if (id.isEmpty()) return "Usage: /flow-cancel RUN";
        api.cancel(user, roles, id);
        return "Cancelled " + id;
    }

    private static JsonNode tryJson(String s) {
        try {
            return Json.parse(s);
        } catch (FlowException e) {
            return Json.M.getNodeFactory().textNode(s);
        }
    }

    private static String[] split(String arg, int n) {
        String[] parts = (arg == null ? "" : arg.trim()).split("\\s+", n);
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = i < parts.length ? parts[i] : "";
        return out;
    }

    static String show(JsonNode run) {
        StringBuilder sb = new StringBuilder("run " + run.path("runId").asText() + ": " + run.path("status").asText());
        JsonNode q = run.path("pending");
        if (!q.isMissingNode()) {
            sb.append("\n").append(q.path("text").asText()).append(" [").append(q.path("kind").asText().toLowerCase())
                    .append(", asks ").append(q.path("who").asText()).append("]");
            for (JsonNode o : q.path("options")) {
                sb.append("\n  ").append(o.path("value").asText()).append(" - ").append(o.path("label").asText());
            }
        }
        if (run.has("result")) sb.append("\nresult: ").append(run.get("result"));
        if (run.has("error")) sb.append("\nerror: ").append(run.get("error").path("message").asText());
        return sb.toString();
    }
}

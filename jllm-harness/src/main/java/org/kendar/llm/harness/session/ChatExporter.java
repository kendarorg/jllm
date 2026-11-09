package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.JsonNode;

/** Renders a chat's message log (user, assistant, tool calls and results) as Markdown or a standalone HTML page. */
public final class ChatExporter {

    private static final int RESULT_MAX_CHARS = 4000;

    private ChatExporter() {
    }

    public static String markdown(String title, Iterable<JsonNode> messages) {
        StringBuilder sb = new StringBuilder("# ").append(title == null || title.isBlank() ? "Chat" : title).append("\n");
        for (JsonNode m : messages) {
            String role = m.path("role").asText("?");
            JsonNode content = m.path("content");
            if (content.isTextual()) {
                section(sb, role, content.asText());
                continue;
            }
            for (JsonNode b : content) {
                switch (b.path("type").asText()) {
                    case "text" -> section(sb, role, b.path("text").asText());
                    case "thinking" -> section(sb, "thinking", b.path("thinking").asText());
                    case "tool_use" -> sb.append("\n### tool call: ").append(b.path("name").asText()).append("\n\n```json\n")
                            .append(b.path("input")).append("\n```\n");
                    case "tool_result" -> {
                        String r = b.path("content").isTextual() ? b.path("content").asText() : b.path("content").toString();
                        if (r.length() > RESULT_MAX_CHARS) r = r.substring(0, RESULT_MAX_CHARS) + "\n... (truncated)";
                        sb.append("\n### tool result").append(b.path("is_error").asBoolean() ? " (error)" : "")
                                .append("\n\n````\n").append(r).append("\n````\n");
                    }
                    default -> {
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String html(String title, Iterable<JsonNode> messages) {
        String md = markdown(title, messages);
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escape(title == null ? "Chat" : title)
                + "</title><style>body{font-family:sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem}"
                + "pre{white-space:pre-wrap;word-wrap:break-word;font-family:ui-monospace,Consolas,monospace}"
                + "</style></head><body><pre>" + escape(md) + "</pre></body></html>\n";
    }

    private static void section(StringBuilder sb, String role, String text) {
        if (text == null || text.isBlank()) return;
        sb.append("\n## ").append(role).append("\n\n").append(text.strip()).append("\n");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

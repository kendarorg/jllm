package org.kendar.llm.eval.grade;

import com.fasterxml.jackson.databind.JsonNode;

/** Text extraction from request messages (Anthropic-style blocks or plain strings). */
final class Msgs {

    private Msgs() {
    }

    static String role(JsonNode msg) {
        return msg.path("role").asText("");
    }

    /** All text of a message: text blocks, tool results, tool inputs. */
    static String text(JsonNode msg) {
        JsonNode c = msg.path("content");
        if (c.isTextual()) return c.asText();
        if (!c.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : c) {
            switch (b.path("type").asText("")) {
                case "text" -> sb.append(b.path("text").asText()).append('\n');
                case "tool_result" -> sb.append(blockText(b.path("content"))).append('\n');
                case "tool_use" -> sb.append(b.path("input").toString()).append('\n');
                default -> {
                }
            }
        }
        return sb.toString();
    }

    static String blockText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.isMissingNode() || content.isNull() ? "" : content.toString();
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : content) sb.append(b.path("text").asText()).append('\n');
        return sb.toString();
    }

    static boolean masked(JsonNode msg) {
        String t = text(msg);
        return t.contains(StrategyChecks.MASKED) && t.contains(StrategyChecks.RECALL_MARKER);
    }
}

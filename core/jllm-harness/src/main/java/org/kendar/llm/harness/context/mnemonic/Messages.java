package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/** Message helpers: canonical form, content hashes, token estimates and plain-text rendering. */
final class Messages {

    /** Characters per token of the estimate (before calibration against measured usage). */
    static final double CHARS_PER_TOKEN = org.kendar.llm.harness.context.TokenEstimate.CHARS_PER_TOKEN;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Messages() {
    }

    static long tokens(String s) {
        return s == null ? 0 : (long) Math.ceil(s.length() / CHARS_PER_TOKEN);
    }

    static long tokens(JsonNode n) {
        return n == null ? 0 : tokens(n.toString());
    }

    /**
     * Content hash of the message at {@code index}: sha256 of the index and the canonical form (reasoning blocks
     * dropped, object keys sorted, CRLF and trailing whitespace normalized).
     */
    static String hash(int index, JsonNode message) {
        return sha256(index + "\n" + canonical(message).toString());
    }

    static String sha256(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode canonical(JsonNode n) {
        if (n.isTextual()) return TextNode.valueOf(n.asText().replace("\r\n", "\n").stripTrailing());
        if (n.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode e : n) {
                if (isThinking(e)) continue;
                out.add(canonical(e));
            }
            return out;
        }
        if (n.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) names.add(it.next());
            names.sort(null);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String name : names) out.set(name, canonical(n.get(name)));
            return out;
        }
        return n;
    }

    static boolean isThinking(JsonNode block) {
        String t = block.path("type").asText();
        return "thinking".equals(t) || "redacted_thinking".equals(t);
    }

    static String role(JsonNode msg) {
        return msg.path("role").asText();
    }

    /** A user message made only of text (no tool results): the start of a human turn. */
    static boolean isPlainUserText(JsonNode msg) {
        if (!"user".equals(role(msg))) return false;
        JsonNode c = msg.path("content");
        if (c.isTextual()) return true;
        if (!c.isArray() || c.isEmpty()) return false;
        for (JsonNode b : c) {
            if (!"text".equals(b.path("type").asText())) return false;
        }
        return true;
    }

    /** The text of a tool_result block, whatever shape its content has. */
    static String resultText(JsonNode block) {
        JsonNode c = block.path("content");
        if (c.isTextual()) return c.asText();
        if (c.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : c) {
                if (b.has("text")) sb.append(b.path("text").asText()).append('\n');
                else sb.append(b).append('\n');
            }
            return sb.toString().stripTrailing();
        }
        return c.isMissingNode() ? "" : c.toString();
    }

    /** Plain-text form of a whole message, for search and for verbatim recall. */
    static String text(JsonNode msg) {
        JsonNode c = msg.path("content");
        if (c.isTextual()) return c.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : c) {
            switch (b.path("type").asText()) {
                case "text" -> sb.append(b.path("text").asText());
                case "tool_use" -> sb.append("[tool_use ").append(b.path("name").asText()).append("] ")
                        .append(pretty(b.path("input")));
                case "tool_result" -> sb.append("[tool_result]\n").append(resultText(b));
                case "thinking" -> sb.append("[reasoning]\n").append(b.path("thinking").asText());
                default -> { /* images, redacted reasoning: nothing to search */ }
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    static String pretty(JsonNode n) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (Exception e) {
            return n.toString();
        }
    }

    /** The tool_use block a tool_result answers: in the assistant message just before {@code index}. */
    static JsonNode toolUse(ArrayNode log, int index, String toolUseId) {
        for (int i = index - 1; i >= 0 && i >= index - 2; i--) {
            JsonNode c = log.get(i).path("content");
            if (!c.isArray()) continue;
            for (JsonNode b : c) {
                if ("tool_use".equals(b.path("type").asText()) && toolUseId.equals(b.path("id").asText())) return b;
            }
        }
        return null;
    }

    /** {@code name(arg=value, arg=value)} with at most two short arguments. */
    static String callSummary(JsonNode toolUse) {
        if (toolUse == null) return "tool";
        StringBuilder sb = new StringBuilder(toolUse.path("name").asText("tool")).append('(');
        int n = 0;
        for (Iterator<String> it = toolUse.path("input").fieldNames(); it.hasNext() && n < 2; n++) {
            String k = it.next();
            JsonNode v = toolUse.path("input").get(k);
            String s = v.isTextual() ? v.asText() : v.toString();
            s = s.replace('\n', ' ');
            if (s.length() > 60) s = s.substring(0, 60) + "…";
            if (n > 0) sb.append(", ");
            sb.append(k).append('=').append(s);
        }
        return sb.append(')').toString();
    }
}

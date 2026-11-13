package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient reading of a tool call cut off at the output limit, whose arguments are incomplete. Handles JSON arguments
 * (an unterminated {@code "content": "...}) and the Qwen XML call format ({@code <function=x><parameter=path>...}).
 */
public final class PartialToolCall {

    /** Input key holding arguments that are not valid JSON. */
    public static final String RAW = "_raw_arguments";

    private static final String OPEN = "<tool_call>";
    private static final String CLOSE = "</tool_call>";
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"\\\\]+)\"");
    private static final Pattern XML_NAME = Pattern.compile("<function=([^>\\s]+)>");

    private PartialToolCall() {
    }

    /** Start of a text-format call that was opened but never closed, or -1. */
    public static int openTextCall(String text) {
        int at = text.lastIndexOf(OPEN);
        return at >= 0 && text.indexOf(CLOSE, at) < 0 ? at : -1;
    }

    /** Tool name of a text-format call fragment, or null. */
    public static String textCallName(String fragment) {
        Matcher m = XML_NAME.matcher(fragment);
        if (m.find()) return m.group(1);
        m = JSON_NAME.matcher(fragment);
        return m.find() ? m.group(1) : null;
    }

    /** A string argument of a cut-off call, from the parsed input or read from its raw arguments; null if absent. */
    public static String field(JsonNode input, String name) {
        if (input.path(name).isTextual()) return input.path(name).asText();
        String raw = input.path(RAW).asText(null);
        if (raw == null) return null;
        String v = jsonField(raw, name);
        return v != null ? v : xmlField(raw, name);
    }

    /** Value of {@code "name": "..."}; an unterminated string runs to the end, a dangling escape is dropped. */
    static String jsonField(String raw, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"").matcher(raw);
        if (!m.find()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = m.end(); i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') break;
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= raw.length()) break;
            char e = raw.charAt(i);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 >= raw.length()) return sb.toString();
                    try {
                        sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException ex) {
                        return sb.toString();
                    }
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
        return sb.toString();
    }

    /** Value of {@code <parameter=name>...</parameter>}; an unclosed parameter runs to the end. */
    static String xmlField(String raw, String name) {
        String open = "<parameter=" + name + ">";
        int at = raw.indexOf(open);
        if (at < 0) return null;
        int from = at + open.length();
        if (raw.startsWith("\n", from)) from++;
        int end = raw.indexOf("</parameter>", from);
        if (end < 0) return raw.substring(from);
        String v = raw.substring(from, end);
        return v.endsWith("\n") ? v.substring(0, v.length() - 1) : v;
    }
}

package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.kendar.llm.harness.workflows.WorkflowApi.QuestionKind;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Something a flow needs from a person. Three kinds, so every front end can show it properly.
 *
 * @param key      stable key; the same context always yields the same key, so replays find the recorded answer
 * @param who      {@code owner}, an operator role name, or {@code customer}
 * @param pattern  optional regex a text answer must match; {@code number} accepts only numbers
 * @param facts    anything to show next to the question (classification, triggering rule)
 */
public record Question(String key, QuestionKind kind, String title, String text, List<Option> options, int min, int max,
                       boolean required, String who, String pattern, JsonNode facts) {

    /** A stable value the flow uses and a label for display. */
    public record Option(String value, String label) {
    }

    public static Question text(String key, String text) {
        return new Question(key, QuestionKind.TEXT, text, text, List.of(), 0, 0, true, "owner", null, null);
    }

    public static Question single(String key, String text, List<Option> options) {
        return new Question(key, QuestionKind.SINGLE, text, text, options, 1, 1, true, "owner", null, null);
    }

    public static Question multi(String key, String text, List<Option> options, int min, int max) {
        return new Question(key, QuestionKind.MULTI, text, text, options, min, max, min > 0, "owner", null, null);
    }

    public Question withWho(String who) {
        return new Question(key, kind, title, text, options, min, max, required, who, pattern, facts);
    }

    public Question withFacts(JsonNode f) {
        return new Question(key, kind, title, text, options, min, max, required, who, pattern, f);
    }

    public Question withPattern(String p) {
        return new Question(key, kind, title, text, options, min, max, required, who, p, facts);
    }

    /** An answer that does not fit is refused and the question stays open. */
    public void validate(JsonNode answer) {
        switch (kind) {
            case TEXT -> {
                if (answer == null || !answer.isValueNode() || answer.isNull()) bad("answer must be text");
                String t = answer.asText();
                if (required && t.isBlank()) bad("an answer is required");
                if ("number".equals(pattern)) {
                    try {
                        Double.parseDouble(t.trim());
                    } catch (NumberFormatException e) {
                        bad("'" + t + "' is not a number");
                    }
                } else if (pattern != null && !t.isEmpty() && !Pattern.matches(pattern, t)) {
                    bad("'" + t + "' does not match " + pattern);
                }
            }
            case SINGLE -> {
                if (answer == null || !answer.isTextual() || !values().contains(answer.asText())) {
                    bad("pick exactly one of " + values());
                }
            }
            case MULTI -> {
                if (answer == null || !answer.isArray()) bad("pick from " + values());
                Set<String> seen = new HashSet<>();
                for (JsonNode n : answer) {
                    if (!n.isTextual() || !values().contains(n.asText())) bad("'" + n.asText() + "' is not an option");
                    if (!seen.add(n.asText())) bad("'" + n.asText() + "' picked twice");
                }
                if (seen.size() < min) bad("pick at least " + min);
                if (max > 0 && seen.size() > max) bad("pick at most " + max);
            }
        }
    }

    private List<String> values() {
        return options.stream().map(Option::value).toList();
    }

    private static void bad(String msg) {
        throw new FlowException(FlowException.Kind.SHAPE, msg);
    }

    public ObjectNode toJson() {
        ObjectNode o = Json.M.createObjectNode();
        o.put("key", key).put("kind", kind.name()).put("title", title).put("text", text);
        o.put("min", min).put("max", max).put("required", required).put("who", who);
        if (pattern != null) o.put("pattern", pattern);
        if (facts != null) o.set("facts", facts);
        ArrayNode a = o.putArray("options");
        for (Option op : options) a.addObject().put("value", op.value()).put("label", op.label());
        return o;
    }

    public static Question fromJson(JsonNode o) {
        List<Option> opts = new java.util.ArrayList<>();
        for (JsonNode n : o.path("options")) opts.add(new Option(n.path("value").asText(), n.path("label").asText()));
        return new Question(o.path("key").asText(), QuestionKind.valueOf(o.path("kind").asText()), o.path("title").asText(),
                o.path("text").asText(), opts, o.path("min").asInt(), o.path("max").asInt(),
                o.path("required").asBoolean(true), o.path("who").asText("owner"),
                o.has("pattern") ? o.get("pattern").asText() : null, o.has("facts") ? o.get("facts") : null);
    }
}

package org.kendar.llm.harness.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regex permission rules from the settings: {@code deny}, {@code ask} and {@code allow}, each a list of
 * {@code toolPattern} or {@code toolPattern(argPattern)}. The tool pattern is matched against the whole tool name;
 * the argument pattern is searched in the main argument of the call ({@code command} for bash, {@code path} for file
 * tools, {@code url} for web_fetch, {@code query} for searches, the compact JSON for other tools). Evaluation order is
 * deny, ask, allow; the first match wins.
 * <p>
 * A regex on a shell command line is a guard rail, not a security boundary (quoting, variables, scripts): use an
 * isolating sandbox executor for real containment.
 */
public final class PermissionRules {

    public enum Kind { DENY, ASK, ALLOW }

    /** The rule that matched a call. */
    public record Match(Kind kind, String rule) {
    }

    private record Rule(Kind kind, String text, Pattern tool, Pattern arg) {
        boolean matches(String toolName, JsonNode input) {
            if (!tool.matcher(toolName).matches()) return false;
            return arg == null || arg.matcher(mainArgument(toolName, input)).find();
        }
    }

    private static final PermissionRules NONE = new PermissionRules(List.of());

    private final List<Rule> rules;

    private PermissionRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static PermissionRules none() {
        return NONE;
    }

    /**
     * @throws IllegalArgumentException for an invalid regex, naming the list and the index of the rule
     */
    public static PermissionRules parse(List<String> deny, List<String> ask, List<String> allow) {
        List<Rule> all = new ArrayList<>();
        add(all, Kind.DENY, deny);
        add(all, Kind.ASK, ask);
        add(all, Kind.ALLOW, allow);
        return all.isEmpty() ? NONE : new PermissionRules(List.copyOf(all));
    }

    private static void add(List<Rule> out, Kind kind, List<String> texts) {
        if (texts == null) return;
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            String where = "permissions." + kind.name().toLowerCase() + "[" + i + "]";
            if (text == null || text.isBlank()) throw new IllegalArgumentException(where + ": empty rule");
            try {
                int open = text.indexOf('(');
                if (open > 0 && text.endsWith(")")) {
                    out.add(new Rule(kind, text, Pattern.compile(text.substring(0, open)),
                            Pattern.compile(text.substring(open + 1, text.length() - 1))));
                } else {
                    out.add(new Rule(kind, text, Pattern.compile(text), null));
                }
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(where + " '" + text + "': " + e.getDescription(), e);
            }
        }
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** First matching rule in deny, ask, allow order; null when none matches. */
    public Match match(String toolName, JsonNode input) {
        for (Rule r : rules) {
            if (r.matches(toolName, input)) return new Match(r.kind(), r.text());
        }
        return null;
    }

    /** True if a deny rule without argument pattern covers the tool: it is not even offered to the model. */
    public boolean hides(String toolName) {
        for (Rule r : rules) {
            if (r.kind() == Kind.DENY && r.arg() == null && r.tool().matcher(toolName).matches()) return true;
        }
        return false;
    }

    /** One line per rule, for {@code /permissions}. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Rule r : rules) out.add(r.kind().name().toLowerCase() + "  " + r.text());
        return out;
    }

    static String mainArgument(String tool, JsonNode input) {
        String field = switch (tool) {
            case "bash" -> "command";
            case "read_file", "write_file", "edit_file" -> "path";
            case "web_fetch" -> "url";
            case "web_search", "wikipedia_search" -> "query";
            default -> null;
        };
        if (field != null && input != null && input.hasNonNull(field)) {
            String v = input.get(field).asText();
            return "path".equals(field) ? v.replace('\\', '/') : v;
        }
        return input == null ? "" : input.toString();
    }
}

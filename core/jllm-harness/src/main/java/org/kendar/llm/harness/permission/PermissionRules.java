package org.kendar.llm.harness.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
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

    /** @param argFolded the argument pattern, case-insensitive: used for paths on Windows */
    private record Rule(Kind kind, String text, Pattern tool, Pattern arg, Pattern argFolded) {
        boolean matches(String toolName, JsonNode input, Path workDir, boolean windows) {
            if (!tool.matcher(toolName).matches()) return false;
            if (arg == null) return true;
            boolean path = isPathTool(toolName) && input != null && input.hasNonNull("path");
            String value = path ? normalizePath(input.get("path").asText(), workDir, windows)
                    : mainArgument(toolName, input);
            return (path && windows ? argFolded : arg).matcher(value).find();
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PermissionRules.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

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
            int open = text.indexOf('(');
            if (open == 0) {
                // "(...)" has no tool pattern: as a regex it would only match a tool named like the argument
                log.warn("{} '{}' ignored: a rule is toolPattern or toolPattern(argPattern), the tool pattern is "
                        + "missing", where, text);
                continue;
            }
            try {
                if (open > 0 && text.endsWith(")")) {
                    String a = text.substring(open + 1, text.length() - 1);
                    out.add(new Rule(kind, text, Pattern.compile(text.substring(0, open)), Pattern.compile(a),
                            Pattern.compile(a, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
                } else {
                    out.add(new Rule(kind, text, Pattern.compile(text), null, null));
                }
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(where + " '" + text + "': " + e.getDescription(), e);
            }
        }
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** First matching rule in deny, ask, allow order; null when none matches. Paths are normalized lexically only. */
    public Match match(String toolName, JsonNode input) {
        return match(toolName, input, null);
    }

    /**
     * As {@link #match(String, JsonNode)}, with path arguments made relative to {@code workDir} (when inside it) after
     * normalization; on Windows they are also matched case-insensitively, with trailing dots/spaces of each segment
     * dropped and 8.3 short names expanded for paths that exist.
     */
    public Match match(String toolName, JsonNode input, Path workDir) {
        return match(toolName, input, workDir, WINDOWS);
    }

    Match match(String toolName, JsonNode input, Path workDir, boolean windows) {
        for (Rule r : rules) {
            if (r.matches(toolName, input, workDir, windows)) return new Match(r.kind(), r.text());
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

    private static boolean isPathTool(String tool) {
        return switch (tool) {
            case "read_file", "write_file", "edit_file" -> true;
            default -> false;
        };
    }

    /**
     * The form of a path argument the rules see: '/'-separated, {@code .} and {@code ..} segments resolved, repeated
     * separators collapsed, relative to {@code workDir} when it lies inside it (an absolute path elsewhere stays
     * absolute). On Windows each segment loses its trailing dots and spaces ({@code .git.} is {@code .git}) and an
     * existing path is replaced by its real path, which expands 8.3 short names ({@code GIT~1}).
     */
    static String normalizePath(String raw, Path workDir, boolean windows) {
        if (raw == null) return "";
        String s = raw.replace('\\', '/');
        boolean absolute = s.startsWith("/") || s.matches("^[A-Za-z]:.*");
        String drive = "";
        if (s.matches("^[A-Za-z]:.*")) {
            drive = s.substring(0, 2);
            s = s.substring(2);
        }
        java.util.Deque<String> segs = new java.util.ArrayDeque<>();
        for (String seg : s.split("/")) {
            String g = windows ? stripTrailingDotsAndSpaces(seg) : seg;
            if (g.isEmpty() || g.equals(".")) continue;
            if (g.equals("..")) {
                if (!segs.isEmpty() && !segs.peekLast().equals("..")) segs.removeLast();
                else if (!absolute) segs.addLast("..");
                continue;
            }
            segs.addLast(g);
        }
        String joined = String.join("/", segs);
        String result = absolute ? drive + "/" + joined : joined;
        if (workDir == null) return result.isEmpty() ? "." : result;
        try {
            Path root = workDir.toAbsolutePath().normalize();
            Path p = absolute ? Path.of(result) : root.resolve(joined);
            p = p.toAbsolutePath().normalize();
            if (windows) p = realIfExists(p);
            Path realRoot = windows ? realIfExists(root) : root;
            Path base = p.startsWith(realRoot) ? realRoot : p.startsWith(root) ? root : null;
            if (base == null) return p.toString().replace('\\', '/');
            String rel = base.relativize(p).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        } catch (RuntimeException e) {
            return result.isEmpty() ? "." : result;
        }
    }

    private static String stripTrailingDotsAndSpaces(String seg) {
        if (seg.equals(".") || seg.equals("..")) return seg;
        int end = seg.length();
        while (end > 0 && (seg.charAt(end - 1) == '.' || seg.charAt(end - 1) == ' ')) end--;
        return end == 0 ? seg : seg.substring(0, end);
    }

    private static Path realIfExists(Path p) {
        try {
            return java.nio.file.Files.exists(p) ? p.toRealPath() : p;
        } catch (java.io.IOException | SecurityException e) {
            return p;
        }
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

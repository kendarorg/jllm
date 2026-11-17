package org.kendar.llm.eval.grade;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.eval.run.TrialTrace;
import org.kendar.llm.harness.agent.AgentEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Checks on the event trace and the final answer. All run in the TRACE tier. */
public final class TraceChecks {

    private TraceChecks() {
    }

    private static Check check(String name, Function<TrialContext, CheckResult> body) {
        return Check.of(name, Check.Tier.TRACE, body);
    }

    private static List<String> toolNames(TrialTrace t) {
        return t.toolCalls().stream().map(TrialTrace::toolName).toList();
    }

    private static JsonNode input(AgentEvent e) {
        JsonNode d = e.data();
        return d == null ? null : d.path("input");
    }

    public static Check toolCalled(String tool) {
        String n = "toolCalled(" + tool + ")";
        return check(n, ctx -> toolNames(ctx.trace()).contains(tool)
                ? CheckResult.pass(n, tool + " was called")
                : CheckResult.fail(n, tool + " was never called; calls: " + toolNames(ctx.trace())));
    }

    public static Check toolNotCalled(String tool) {
        String n = "toolNotCalled(" + tool + ")";
        return check(n, ctx -> {
            long c = toolNames(ctx.trace()).stream().filter(tool::equals).count();
            return c == 0 ? CheckResult.pass(n, tool + " was not called")
                    : CheckResult.fail(n, tool + " was called " + c + " time(s)");
        });
    }

    /** A call of {@code tool} whose input field {@code field} contains {@code expected} (case-sensitive). */
    public static Check toolCalledWith(String tool, String field, String expected) {
        return toolCalledWith(tool, in -> {
            JsonNode v = in.path(field);
            return !v.isMissingNode() && (v.isTextual() ? v.asText() : v.toString()).contains(expected);
        }, field + " contains '" + expected + "'");
    }

    /** A call of {@code tool} whose input satisfies {@code test}. */
    public static Check toolCalledWith(String tool, Predicate<JsonNode> test, String description) {
        String n = "toolCalledWith(" + tool + ", " + description + ")";
        return check(n, ctx -> {
            List<String> seen = new ArrayList<>();
            for (AgentEvent e : ctx.trace().toolCalls()) {
                if (!tool.equals(TrialTrace.toolName(e))) continue;
                JsonNode in = input(e);
                if (in != null && test.test(in)) return CheckResult.pass(n, "matching call found");
                seen.add(String.valueOf(in));
            }
            return CheckResult.fail(n, seen.isEmpty() ? tool + " was never called" : "no call matches; inputs: " + seen);
        });
    }

    /** The tools were called in this relative order (other calls may be in between). */
    public static Check toolOrder(String... tools) {
        String n = "toolOrder(" + String.join(" -> ", tools) + ")";
        return check(n, ctx -> {
            int next = 0;
            for (String called : toolNames(ctx.trace())) {
                if (next < tools.length && tools[next].equals(called)) next++;
            }
            return next == tools.length ? CheckResult.pass(n, "order respected")
                    : CheckResult.fail(n, "order broken at '" + tools[next] + "'; calls: " + toolNames(ctx.trace()));
        });
    }

    public static Check approvalRequested() {
        return check("approvalRequested", ctx -> ctx.trace().events("approval_request").isEmpty()
                ? CheckResult.fail("approvalRequested", "no approval was requested")
                : CheckResult.pass("approvalRequested", ctx.trace().events("approval_request").size() + " request(s)"));
    }

    /** An approval request whose data mentions {@code text} (tool name, host...). */
    public static Check approvalRequested(String text) {
        String n = "approvalRequested(" + text + ")";
        return check(n, ctx -> ctx.trace().events("approval_request").stream()
                .anyMatch(e -> e.data() != null && e.data().toString().contains(text))
                ? CheckResult.pass(n, "approval requested")
                : CheckResult.fail(n, "no approval request mentions '" + text + "'"));
    }

    public static Check approvalNotRequested() {
        return check("approvalNotRequested", ctx -> {
            int c = ctx.trace().events("approval_request").size();
            return c == 0 ? CheckResult.pass("approvalNotRequested", "no approval requested")
                    : CheckResult.fail("approvalNotRequested", c + " approval request(s)");
        });
    }

    public static Check eventPresent(String type) {
        String n = "eventPresent(" + type + ")";
        return check(n, ctx -> ctx.trace().events(type).isEmpty()
                ? CheckResult.fail(n, "no '" + type + "' event")
                : CheckResult.pass(n, ctx.trace().events(type).size() + " '" + type + "' event(s)"));
    }

    public static Check eventAbsent(String type) {
        String n = "eventAbsent(" + type + ")";
        return check(n, ctx -> ctx.trace().events(type).isEmpty()
                ? CheckResult.pass(n, "no '" + type + "' event")
                : CheckResult.fail(n, ctx.trace().events(type).size() + " '" + type + "' event(s)"));
    }

    public static Check maxToolCalls(int max) {
        String n = "maxToolCalls(" + max + ")";
        return check(n, ctx -> {
            int c = ctx.trace().toolCalls().size();
            return c <= max ? CheckResult.pass(n, c + " tool call(s)")
                    : CheckResult.fail(n, c + " tool calls, more than " + max);
        });
    }

    /** No identical call (same tool, same input) more than {@code max} times. */
    public static Check maxDuplicateCalls(int max) {
        String n = "maxDuplicateCalls(" + max + ")";
        return check(n, ctx -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (AgentEvent e : ctx.trace().toolCalls()) {
                counts.merge(TrialTrace.toolName(e) + " " + input(e), 1, Integer::sum);
            }
            var worst = counts.entrySet().stream().max(Map.Entry.comparingByValue());
            if (worst.isEmpty() || worst.get().getValue() <= max) {
                return CheckResult.pass(n, "most repeated call: " + worst.map(Map.Entry::getValue).orElse(0) + "x");
            }
            return CheckResult.fail(n, worst.get().getValue() + "x " + shorten(worst.get().getKey()));
        });
    }

    public static Check noRunError() {
        return check("noRunError", ctx -> ctx.trace().error() == null
                ? CheckResult.pass("noRunError", "run ended normally")
                : CheckResult.fail("noRunError", shorten(ctx.trace().error())));
    }

    /** Final answer contains the text, ignoring case. */
    public static Check answerContains(String text) {
        String n = "answerContains(" + text + ")";
        return check(n, ctx -> lower(ctx.finalAnswer()).contains(text.toLowerCase())
                ? CheckResult.pass(n, "found in the answer")
                : CheckResult.fail(n, "not in the answer: " + shorten(ctx.finalAnswer())));
    }

    public static Check answerNotContains(String text) {
        String n = "answerNotContains(" + text + ")";
        return check(n, ctx -> lower(ctx.finalAnswer()).contains(text.toLowerCase())
                ? CheckResult.fail(n, "found in the answer")
                : CheckResult.pass(n, "not in the answer"));
    }

    public static Check answerMatches(String regex) {
        String n = "answerMatches(" + regex + ")";
        Pattern p = Pattern.compile(regex, Pattern.DOTALL);
        return check(n, ctx -> p.matcher(ctx.finalAnswer() == null ? "" : ctx.finalAnswer()).find()
                ? CheckResult.pass(n, "pattern found")
                : CheckResult.fail(n, "pattern not found in: " + shorten(ctx.finalAnswer())));
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    static String shorten(String s) {
        if (s == null) return "null";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 120 ? one : one.substring(0, 117) + "...";
    }
}

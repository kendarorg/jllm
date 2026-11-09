package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.agent.AgentEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns agent events into terminal output. Fancy mode (a real terminal) shows a tool call and its result as one
 * collapsed block that {@link #expand} prints in full; plain mode keeps the line format scripts rely on.
 * Approval requests and the end of a run are not handled here.
 */
final class Renderer {

    static final String RESET = "\u001b[0m", DIM = "\u001b[2m", BOLD = "\u001b[1m", RED = "\u001b[31m",
            GREEN = "\u001b[32m", YELLOW = "\u001b[33m", CYAN = "\u001b[36m";
    private static final int ARG_MAX = 90;
    private static final int KEPT_CALLS = 200;
    private static final String[] ARG_KEYS = {"path", "file_path", "command", "pattern", "url", "query", "name"};

    /** One finished tool call, kept so it can be expanded later. */
    record Call(int n, String name, JsonNode input, String output, boolean error) {
    }

    private final Consumer<String> out;
    private final Consumer<String> err;
    private final boolean fancy;
    private final Map<String, JsonNode[]> pending = new HashMap<>(); // id -> {input}
    private final List<Call> calls = new ArrayList<>();
    private int counter;

    Renderer(Consumer<String> out, Consumer<String> err, boolean fancy) {
        this.out = out;
        this.err = err;
        this.fancy = fancy;
    }

    synchronized void render(AgentEvent ev) {
        JsonNode d = ev.data();
        switch (ev.type()) {
            case "text" -> out.accept(fancy ? CYAN + "● " + RESET + d.path("text").asText() : d.path("text").asText());
            case "tool_call" -> {
                if (fancy) pending.put(d.path("id").asText(), new JsonNode[]{d.path("input")});
                else out.accept("[tool] " + d.path("name").asText() + " " + d.path("input"));
            }
            case "tool_result" -> toolResult(d);
            case "warning" -> out.accept(color(YELLOW, "[warning] " + d.path("message").asText()));
            case "error" -> err.accept(color(RED, "[error] " + d.path("message").asText()));
            case "compaction" -> out.accept(color(DIM, "[compaction] " + d));
            case "plan_ready" -> out.accept("[plan] " + d.path("message").asText());
            case "cancelled" -> out.accept(color(YELLOW, "[cancelled]"));
            case "steer_queued" -> out.accept(color(DIM, "[queued " + d.path("kind").asText() + "] " + d.path("text").asText()));
            case "steer_applied" -> out.accept(color(DIM, "[applied] " + d.path("text").asText()));
            default -> { }
        }
    }

    private void toolResult(JsonNode d) {
        String name = d.path("name").asText();
        boolean error = d.path("isError").asBoolean();
        String output = d.path("output").asText();
        if (!fancy) {
            out.accept("[result" + (error ? ", error" : "") + "] " + abbreviate(output));
            return;
        }
        JsonNode[] p = pending.remove(d.path("id").asText());
        JsonNode input = p == null ? d.path("input") : p[0];
        Call c = new Call(++counter, name, input, output, error);
        calls.add(c);
        if (calls.size() > KEPT_CALLS) calls.remove(0);
        out.accept(header(c) + "\n" + collapsed(c));
    }

    private static String header(Call c) {
        return (c.error() ? RED : GREEN) + "⏺ " + RESET + BOLD + c.name() + RESET + "(" + argSummary(c.input()) + ")";
    }

    private static String collapsed(Call c) {
        String[] lines = c.output().strip().split("\n");
        String first = lines.length == 0 ? "" : lines[0];
        if (first.length() > ARG_MAX) first = first.substring(0, ARG_MAX) + "…";
        String more = lines.length > 1 ? " … +" + (lines.length - 1) + " lines" : "";
        return DIM + "  ⎿ " + RESET + (c.error() ? RED : "") + (first.isEmpty() ? "(no output)" : first) + RESET
                + DIM + more + "  [/expand " + c.n() + "]" + RESET;
    }

    /** Prints a kept call in full: {@code arg} is a call number, empty for the last one. */
    synchronized void expand(String arg) {
        if (calls.isEmpty()) {
            out.accept("No tool call to expand.");
            return;
        }
        Call c = null;
        if (arg == null || arg.isBlank()) c = calls.get(calls.size() - 1);
        else if (arg.strip().matches("\\d+")) {
            int n = Integer.parseInt(arg.strip());
            for (Call k : calls) if (k.n() == n) c = k;
        }
        if (c == null) {
            out.accept("No such tool call (" + calls.get(0).n() + ".." + calls.get(calls.size() - 1).n() + " kept).");
            return;
        }
        out.accept(header(c) + "\n" + DIM + "  input: " + RESET + c.input() + "\n" + DIM + "  output:" + RESET + "\n"
                + (c.error() ? RED : "") + c.output() + RESET);
    }

    /** The most useful argument of a call, one line. */
    static String argSummary(JsonNode input) {
        for (String k : ARG_KEYS) {
            if (input.hasNonNull(k) && input.get(k).isValueNode()) return cut(input.get(k).asText());
        }
        return cut(input.toString());
    }

    private static String cut(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > ARG_MAX ? one.substring(0, ARG_MAX) + "…" : one;
    }

    private String color(String c, String s) {
        return fancy ? c + s + RESET : s;
    }

    static String abbreviate(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }
}

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
    /** Fancy mode: text blocks being streamed, by block index; whole lines are printed as they complete. */
    private final java.util.TreeMap<Integer, Streamed> streamed = new java.util.TreeMap<>();

    /** A block being streamed: what arrived and how much of it was printed. */
    private static final class Streamed {
        final StringBuilder text = new StringBuilder();
        int printed;
    }

    Renderer(Consumer<String> out, Consumer<String> err, boolean fancy) {
        this.out = out;
        this.err = err;
        this.fancy = fancy;
    }

    synchronized void render(AgentEvent ev) {
        JsonNode d = ev.data();
        switch (ev.type()) {
            case "text" -> text(d.path("text").asText());
            case "text_delta" -> {
                if (fancy) delta(d.path("block").asInt(0), d.path("text").asText(""));
            }
            case "text_reset" -> {
                if (fancy && streamed.values().stream().anyMatch(s -> s.printed > 0)) {
                    out.accept(color(DIM, "[retrying: the partial answer above is discarded]"));
                }
                streamed.clear();
            }
            case "stuck" -> out.accept(color(YELLOW, "[stuck] " + clean(d.path("message").asText(d.path("pattern").asText()))
                    + " (" + clean(d.path("action").asText()) + ")"));
            case "verify_failed" -> out.accept(color(YELLOW, "[stop check failed, attempt " + d.path("attempt").asInt()
                    + "] ") + clean(d.path("message").asText()));
            case "tool_call" -> {
                if (fancy) pending.put(d.path("id").asText(), new JsonNode[]{d.path("input")});
                else out.accept("[tool] " + clean(d.path("name").asText()) + " " + clean(d.path("input").toString()));
            }
            case "tool_result" -> toolResult(d);
            case "warning" -> out.accept(color(YELLOW, "[warning] " + clean(d.path("message").asText())));
            case "retry" -> out.accept(color(YELLOW, "[retry] " + clean(d.path("message").asText())));
            case "login_required" -> out.accept(color(YELLOW, "[login] " + clean(d.path("source").asText())
                    + " is not connected: run /login " + clean(d.path("source").asText()) + ", then ask to retry"));
            case "login" -> out.accept(color(YELLOW, "[login] " + clean(d.path("message").asText())));
            case "error" -> {
                closeStreamed();
                err.accept(color(RED, "[error] " + clean(d.path("message").asText())));
            }
            case "compaction" -> out.accept(color(DIM, "[compaction] " + clean(d.toString())));
            case "plan_ready" -> out.accept("[plan] " + clean(d.path("message").asText()));
            case "cancelled" -> {
                closeStreamed();
                out.accept(color(YELLOW, "[cancelled]"));
            }
            case "steer_queued" -> out.accept(color(DIM, "[queued " + clean(d.path("kind").asText()) + "] " + clean(d.path("text").asText())));
            case "steer_applied" -> out.accept(color(DIM, "[applied] " + clean(d.path("text").asText())));
            default -> { }
        }
    }

    /** A final text block: what streaming already printed is not printed again. */
    private void text(String full) {
        if (!fancy) {
            out.accept(clean(full));
            return;
        }
        var first = streamed.pollFirstEntry();
        if (first != null) {
            Streamed s = first.getValue();
            String shown = s.text.substring(0, s.printed);
            if (s.printed > 0 && full.startsWith(shown)) {
                String rest = full.substring(s.printed);
                if (!rest.isEmpty()) out.accept(clean(rest));
                return;
            }
        }
        out.accept(CYAN + "● " + RESET + clean(full));
    }

    /** Prints the lines of a streamed block that are complete. */
    private void delta(int block, String piece) {
        Streamed s = streamed.computeIfAbsent(block, k -> new Streamed());
        s.text.append(piece);
        int nl = s.text.lastIndexOf("\n");
        if (nl < s.printed) return;
        String lines = s.text.substring(s.printed, nl);
        out.accept((s.printed == 0 ? CYAN + "● " + RESET : "") + clean(lines));
        s.printed = nl + 1;
    }

    /** The run stopped mid-stream: print the incomplete last line, marked. */
    private void closeStreamed() {
        for (Streamed s : streamed.values()) {
            String rest = s.text.substring(s.printed);
            if (!rest.isEmpty() || s.printed > 0) {
                out.accept((s.printed == 0 ? CYAN + "● " + RESET : "") + clean(rest) + color(DIM, " …"));
            }
        }
        streamed.clear();
    }

    private void toolResult(JsonNode d) {
        String name = clean(d.path("name").asText());
        boolean error = d.path("isError").asBoolean();
        String output = clean(d.path("output").asText());
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
        out.accept(header(c) + "\n" + DIM + "  input: " + RESET + clean(String.valueOf(c.input())) + "\n" + DIM + "  output:" + RESET + "\n"
                + (c.error() ? RED : "") + c.output() + RESET);
    }

    /** The most useful argument of a call, one line. */
    static String argSummary(JsonNode input) {
        for (String k : ARG_KEYS) {
            if (input.hasNonNull(k) && input.get(k).isValueNode()) return cut(clean(input.get(k).asText()));
        }
        return cut(clean(input.toString()));
    }

    private static String cut(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > ARG_MAX ? one.substring(0, ARG_MAX) + "…" : one;
    }

    /** ESC sequences (CSI, OSC, DCS/APC/PM/SOS strings, two-byte escapes). */
    private static final java.util.regex.Pattern ESCAPES = java.util.regex.Pattern.compile(
            "\u001b\\[[0-?]*[ -/]*[@-~]"                         // CSI
                    + "|\u001b[\\]PX^_][^\u0007\u001b]*(?:\u0007|\u001b\\\\)?" // OSC, DCS, SOS, PM, APC
                    + "|\u001b[ -/]*[0-~]?");                     // other escapes, a lone ESC

    /**
     * Model and tool text without terminal control: escape sequences and C0/C1 control characters are removed
     * (newlines and tabs stay; a CR is kept only before a newline), so output cannot move the cursor, retitle the
     * window, hide text or forge the prompt.
     */
    static String clean(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String t = ESCAPES.matcher(s).replaceAll("");
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || c == '\t') b.append(c);
            else if (c == '\r') {
                if (i + 1 < t.length() && t.charAt(i + 1) == '\n') b.append(c);
            } else if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) {
                continue;
            } else b.append(c);
        }
        return b.toString();
    }

    private String color(String c, String s) {
        return fancy ? c + s + RESET : s;
    }

    static String abbreviate(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }
}

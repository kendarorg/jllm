package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.eval.report.ResultLoader.CaseRun;
import org.kendar.llm.eval.report.ResultLoader.Check;
import org.kendar.llm.eval.report.ResultLoader.Trial;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Step 9 tooling: exports a balanced sample of judged trials for human labelling (CSV or JSON by file extension). */
public final class LabelExport {

    static final List<String> COLUMNS = List.of("id", "feature", "case", "strategy", "trial", "rubric",
            "judgeVerdict", "trace", "finalAnswer", "humanVerdict");

    private LabelExport() {
    }

    /** One trial to label; humanVerdict is filled in by the human ("pass" or "fail"). */
    public record Row(String id, String feature, String caseName, String strategy, int trial, String rubric,
                      String judgeVerdict, String trace, String finalAnswer, String humanVerdict) {
    }

    /**
     * Judged trials: cases with a rubric and a judge/rubric check, or a rubric case that passed (which implies the
     * judge passed). Half pass, half fail by judge verdict, spread round-robin over rubrics; the shortage of one
     * side is filled from the other.
     */
    public static List<Row> export(Path runDir, Path file, int count) {
        List<Row> pass = new ArrayList<>();
        List<Row> fail = new ArrayList<>();
        for (CaseRun c : ResultLoader.load(runDir).cases()) {
            if (c.rubric() == null) continue;
            for (Trial t : c.trials()) {
                String v = verdict(t);
                if (v == null) continue;
                Row r = new Row(c.key() + "#" + t.trial(), c.feature(), c.name(), c.strategy(), t.trial(), c.rubric(), v,
                        t.traceFile() == null ? "" : t.traceFile().toString(), clip(t.finalAnswer()), "");
                (v.equals("pass") ? pass : fail).add(r);
            }
        }
        int half = count / 2;
        int takePass = Math.min(pass.size(), Math.max(half, count - fail.size()));
        int takeFail = Math.min(fail.size(), count - takePass);
        takePass = Math.min(pass.size(), count - takeFail);
        List<Row> out = new ArrayList<>(spread(pass, takePass));
        out.addAll(spread(fail, takeFail));
        out.sort((a, b) -> a.id().compareTo(b.id()));
        write(file, out);
        return out;
    }

    static String verdict(Trial t) {
        boolean any = false, failed = false;
        for (Check k : t.checks()) {
            String n = k.name().toLowerCase(Locale.ROOT);
            if (!n.contains("judge") && !n.contains("rubric")) continue;
            any = true;
            if (k.failed()) failed = true;
        }
        if (any) return failed ? "fail" : "pass";
        return t.passed() ? "pass" : null;
    }

    private static List<Row> spread(List<Row> rows, int n) {
        Map<String, Deque<Row>> byRubric = new TreeMap<>();
        rows.stream().sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(r -> byRubric.computeIfAbsent(r.rubric(), k -> new ArrayDeque<>()).add(r));
        List<Row> out = new ArrayList<>();
        while (out.size() < n) {
            boolean moved = false;
            for (Deque<Row> d : byRubric.values()) {
                if (out.size() >= n) break;
                if (!d.isEmpty()) {
                    out.add(d.poll());
                    moved = true;
                }
            }
            if (!moved) break;
        }
        return out;
    }

    private static String clip(String s) {
        return s != null && s.length() > 2000 ? s.substring(0, 2000) + "..." : s == null ? "" : s;
    }

    static void write(Path file, List<Row> rows) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (file.getFileName().toString().endsWith(".csv")) {
                StringBuilder sb = new StringBuilder(String.join(",", COLUMNS)).append("\r\n");
                for (Row r : rows) {
                    List<String> f = List.of(r.id(), r.feature(), r.caseName(), r.strategy(), String.valueOf(r.trial()),
                            r.rubric(), r.judgeVerdict(), r.trace(), r.finalAnswer(), r.humanVerdict());
                    sb.append(String.join(",", f.stream().map(LabelExport::quote).toList())).append("\r\n");
                }
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            } else {
                ArrayNode a = ResultLoader.MAPPER.createArrayNode();
                for (Row r : rows) {
                    ObjectNode o = a.addObject();
                    o.put("id", r.id());
                    o.put("feature", r.feature());
                    o.put("case", r.caseName());
                    o.put("strategy", r.strategy());
                    o.put("trial", r.trial());
                    o.put("rubric", r.rubric());
                    o.put("judgeVerdict", r.judgeVerdict());
                    o.put("trace", r.trace());
                    o.put("finalAnswer", r.finalAnswer());
                    o.put("humanVerdict", r.humanVerdict());
                }
                Files.writeString(file, ResultLoader.MAPPER.enable(SerializationFeature.INDENT_OUTPUT)
                        .writeValueAsString(a), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Row> read(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<Row> out = new ArrayList<>();
            if (file.getFileName().toString().endsWith(".csv")) {
                List<List<String>> recs = parseCsv(text);
                if (recs.isEmpty()) return out;
                List<String> head = recs.get(0);
                for (List<String> r : recs.subList(1, recs.size())) {
                    if (r.size() < head.size()) continue;
                    out.add(new Row(r.get(head.indexOf("id")), r.get(head.indexOf("feature")),
                            r.get(head.indexOf("case")), r.get(head.indexOf("strategy")),
                            Integer.parseInt(r.get(head.indexOf("trial"))), r.get(head.indexOf("rubric")),
                            r.get(head.indexOf("judgeVerdict")), r.get(head.indexOf("trace")),
                            r.get(head.indexOf("finalAnswer")), r.get(head.indexOf("humanVerdict"))));
                }
            } else {
                for (JsonNode o : ResultLoader.MAPPER.readTree(text))
                    out.add(new Row(o.path("id").asText(), o.path("feature").asText(), o.path("case").asText(),
                            o.path("strategy").asText(), o.path("trial").asInt(), o.path("rubric").asText(),
                            o.path("judgeVerdict").asText(), o.path("trace").asText(), o.path("finalAnswer").asText(),
                            o.path("humanVerdict").asText()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String s) {
        String v = s == null ? "" : s;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    static List<List<String>> parseCsv(String t) {
        List<List<String>> recs = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else q = false;
                } else f.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') {
                cur.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
                cur.add(f.toString());
                f.setLength(0);
                if (!(cur.size() == 1 && cur.get(0).isEmpty())) recs.add(cur);
                cur = new ArrayList<>();
            } else f.append(c);
        }
        if (f.length() > 0 || !cur.isEmpty()) {
            cur.add(f.toString());
            recs.add(cur);
        }
        return recs;
    }
}

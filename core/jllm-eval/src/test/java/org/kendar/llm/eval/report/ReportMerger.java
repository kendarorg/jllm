package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.HarnessPricing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Merges several run folders into cross-model tables (merged.json, merged.md, merged.html in the output folder). */
public final class ReportMerger {

    private ReportMerger() {
    }

    /** @return the merged document that was written */
    public static JsonNode merge(List<Path> runDirs, Path out) {
        List<JsonNode> sums = new ArrayList<>();
        for (Path p : runDirs)
            sums.add(RunStats.summarize(ResultLoader.load(p), new HarnessPricing(0, 0, 0, 0), false));

        List<String> labels = new ArrayList<>();
        for (JsonNode s : sums) {
            String m = s.path("run").path("model").asText();
            labels.add(labels.contains(m) ? m + " (" + s.path("run").path("runFolder").asText() + ")" : m);
        }
        Set<String> judges = new LinkedHashSet<>();
        for (JsonNode s : sums) judges.add(ReportBuilder.judge(s.path("run")));

        ObjectNode root = ResultLoader.MAPPER.createObjectNode();
        ArrayNode warnings = root.putArray("warnings");
        if (judges.size() > 1)
            warnings.add("runs were judged by different judges (" + String.join("; ", judges)
                    + "): quality columns are not directly comparable");
        for (int i = 0; i < sums.size(); i++)
            if (sums.get(i).path("run").path("selfGraded").asBoolean())
                warnings.add(labels.get(i) + " is self-graded");
        ArrayNode runs = root.putArray("runs");
        for (int i = 0; i < sums.size(); i++) {
            ObjectNode r = runs.addObject();
            r.put("label", labels.get(i));
            r.set("summary", sums.get(i));
        }

        List<String> header = new ArrayList<>();
        header.add("metric");
        header.addAll(labels);
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("judge", sums, s -> ReportBuilder.judge(s.path("run"))));
        rows.add(row("self-graded", sums, s -> s.path("run").path("selfGraded").asBoolean() ? "yes" : "no"));
        rows.add(row("pass rate", sums, s -> ReportBuilder.pct(s.path("overall").path("passRate"))));
        rows.add(row("pass^k", sums, s -> ReportBuilder.pct(s.path("overall").path("passKRate"))));
        rows.add(row("tokens/trial", sums, s -> ReportBuilder.num(s.path("overall").path("tokens").path("perTrial"))));
        rows.add(row("cache ratio", sums, s -> ReportBuilder.pct(s.path("overall").path("cacheRatio"))));
        rows.add(row("latency mean/p50/p95 ms", sums, s -> ReportBuilder.lat(s.path("overall").path("latency"))));
        Set<String> features = new TreeSet<>();
        Set<String> strategies = new TreeSet<>();
        for (JsonNode s : sums) {
            s.path("features").fieldNames().forEachRemaining(features::add);
            s.path("strategies").fieldNames().forEachRemaining(strategies::add);
        }
        for (String f : features)
            rows.add(row("feature " + f + " pass rate", sums,
                    s -> ReportBuilder.pct(s.path("features").path(f).path("passRate"))));
        for (String st : strategies) {
            rows.add(row("strategy " + st + " success", sums,
                    s -> ReportBuilder.pct(s.path("strategies").path(st).path("passRate"))));
            rows.add(row("strategy " + st + " retention", sums,
                    s -> ReportBuilder.pct(s.path("strategies").path(st).path("retention"))));
            rows.add(row("strategy " + st + " cache ratio", sums,
                    s -> ReportBuilder.pct(s.path("strategies").path(st).path("cacheRatio"))));
        }

        Doc d = new Doc("Eval comparison across models");
        for (JsonNode w : warnings) d.warn(w.asText());
        d.table(header, rows);
        ReportBuilder.write(out.resolve("merged.json"), root);
        ReportBuilder.writeText(out.resolve("merged.md"), d.markdown());
        ReportBuilder.writeText(out.resolve("merged.html"), d.html());
        return root;
    }

    private static List<String> row(String name, List<JsonNode> sums, java.util.function.Function<JsonNode, String> f) {
        List<String> r = new ArrayList<>();
        r.add(name);
        for (JsonNode s : sums) r.add(f.apply(s));
        return r;
    }
}

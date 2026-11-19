package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.eval.Secrets;
import org.kendar.llm.harness.HarnessPricing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the per-run report (summary.json, report.md, report.html) in the run folder.
 * Money is reported only when a price is set through the system properties {@code eval.price.input},
 * {@code eval.price.output}, {@code eval.price.cacheRead}, {@code eval.price.cacheWrite} (USD per million tokens).
 * The baseline of the model lives in {@code <runDir parent>/baselines/<model>.json}.
 */
public final class ReportBuilder {

    private static final ObjectMapper OUT = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ReportBuilder() {
    }

    /**
     * @param updateBaseline replace the model baseline with this run (after comparing against the old one)
     * @return the summary written to summary.json
     */
    public static JsonNode build(Path runDir, boolean updateBaseline) {
        ResultLoader.Run run = ResultLoader.load(runDir);
        HarnessPricing pricing = new HarnessPricing(price("input"), price("output"), price("cacheRead"),
                price("cacheWrite"));
        boolean priced = price("input") > 0 || price("output") > 0 || price("cacheRead") > 0
                || price("cacheWrite") > 0;
        ObjectNode summary = RunStats.summarize(run, pricing, priced);

        Path baseFile = runDir.toAbsolutePath().getParent().resolve("baselines").resolve(safe(run.model()) + ".json");
        summary.set("baseline", compare(summary, baseFile));
        if (updateBaseline) {
            write(baseFile, baselineDoc(summary));
            ((ObjectNode) summary.get("baseline")).put("updated", true);
        }
        write(runDir.resolve("summary.json"), summary);
        Doc doc = render(summary);
        writeText(runDir.resolve("report.md"), doc.markdown());
        writeText(runDir.resolve("report.html"), doc.html());
        return summary;
    }

    private static double price(String k) {
        return Double.parseDouble(System.getProperty("eval.price." + k, "0"));
    }

    static String safe(String model) {
        return model.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ObjectNode baselineDoc(JsonNode s) {
        ObjectNode b = ResultLoader.MAPPER.createObjectNode();
        b.put("model", s.path("run").path("model").asText());
        b.put("runFolder", s.path("run").path("runFolder").asText());
        b.put("startTime", s.path("run").path("startTime").asText());
        ObjectNode o = b.putObject("overall");
        o.put("passRate", s.path("overall").path("passRate").asDouble());
        o.put("tokensPerTrial", s.path("overall").path("tokens").path("perTrial").asDouble());
        o.put("meanMs", s.path("overall").path("latency").path("meanMs").asDouble());
        ObjectNode f = b.putObject("features");
        s.path("features").fields().forEachRemaining(e -> f.put(e.getKey(), e.getValue().path("passRate").asDouble()));
        ObjectNode c = b.putObject("cases");
        for (JsonNode n : s.path("cases")) {
            ObjectNode cn = c.putObject(n.path("key").asText());
            cn.put("passRate", n.path("passRate").asDouble());
            cn.put("passes", n.path("passes").asInt());
            cn.put("trials", n.path("trials").asInt());
        }
        return b;
    }

    private static ObjectNode compare(JsonNode s, Path baseFile) {
        ObjectNode out = ResultLoader.MAPPER.createObjectNode();
        out.put("file", baseFile.toString());
        if (!Files.isRegularFile(baseFile)) {
            out.put("present", false);
            return out;
        }
        JsonNode base;
        try {
            base = ResultLoader.MAPPER.readTree(baseFile.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.put("present", true);
        out.put("baselineRun", base.path("runFolder").asText());
        out.put("overallDelta", s.path("overall").path("passRate").asDouble()
                - base.path("overall").path("passRate").asDouble());
        out.put("tokensPerTrialDelta", s.path("overall").path("tokens").path("perTrial").asDouble()
                - base.path("overall").path("tokensPerTrial").asDouble());
        out.put("meanMsDelta", s.path("overall").path("latency").path("meanMs").asDouble()
                - base.path("overall").path("meanMs").asDouble());
        ObjectNode fd = out.putObject("featureDeltas");
        Iterator<Map.Entry<String, JsonNode>> fi = s.path("features").fields();
        while (fi.hasNext()) {
            var e = fi.next();
            if (base.path("features").has(e.getKey()))
                fd.put(e.getKey(), e.getValue().path("passRate").asDouble()
                        - base.path("features").path(e.getKey()).asDouble());
        }
        ArrayNode reg = out.putArray("regressions");
        ArrayNode imp = out.putArray("improvements");
        ArrayNode noise = out.putArray("withinNoise");
        for (JsonNode n : s.path("cases")) {
            String key = n.path("key").asText();
            if (!base.path("cases").has(key)) continue;
            JsonNode bc = base.path("cases").path(key);
            double was = bc.path("passRate").asDouble();
            double now = n.path("passRate").asDouble();
            if (Math.abs(now - was) < 1e-9) continue;
            int wasN = bc.path("trials").asInt();
            int nowN = n.path("trials").asInt();
            int wasP = bc.path("passes").asInt();
            int nowP = n.path("passes").asInt();
            // a change counts only when the 95% Wilson intervals of the two runs do not overlap
            ArrayNode target = !Wilson.separated(wasP, wasN, nowP, nowN) ? noise : now < was ? reg : imp;
            ObjectNode d = target.addObject();
            d.put("case", key);
            d.put("was", was);
            d.put("now", now);
            d.put("wasTrials", wasN);
            d.put("nowTrials", nowN);
        }
        return out;
    }

    private static Doc render(JsonNode s) {
        JsonNode run = s.path("run");
        Doc d = new Doc("Eval report: " + run.path("model").asText());
        JsonNode ov = s.path("overall");
        for (JsonNode w : s.path("warnings")) d.warn(w.asText());
        d.p("Judge: " + judge(run) + (run.path("selfGraded").asBoolean() ? " (SELF-GRADED)" : " (independent)")
                + ". Trials per case: " + run.path("trials").asInt() + ". Run: " + run.path("runFolder").asText());
        d.h("Overall");
        d.table(List.of("cases", "trials", "pass rate", "pass^k", "tokens/trial", "cost/trial", "cache ratio",
                        "latency mean/p50/p95 ms"),
                List.of(List.of(ov.path("cases").asText(), ov.path("trials").asText(), pct(ov.path("passRate")),
                        pct(ov.path("passKRate")), num(ov.path("tokens").path("perTrial")), money(ov),
                        pct(ov.path("cacheRatio")), lat(ov.path("latency")))));
        d.h("Features");
        List<List<String>> rows = new ArrayList<>();
        s.path("features").fields().forEachRemaining(e -> rows.add(List.of(e.getKey(),
                e.getValue().path("cases").asText(), pct(e.getValue().path("passRate")),
                pct(e.getValue().path("passKRate")), num(e.getValue().path("tokens").path("perTrial")),
                money(e.getValue()), lat(e.getValue().path("latency")))));
        d.table(List.of("feature", "cases", "pass rate", "pass^k", "tokens/trial", "cost/trial",
                "latency mean/p50/p95 ms"), rows);

        d.h("Strategy comparison");
        List<List<String>> sr = new ArrayList<>();
        s.path("strategies").fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            sr.add(List.of(e.getKey(), v.path("cases").asText(), pct(v.path("retention")), pct(v.path("passRate")),
                    pct(v.path("passKRate")), num(v.path("tokens").path("perTrial")), money(v),
                    pct(v.path("cacheRatio")), v.path("cacheBreaks").asText()));
        });
        d.table(List.of("strategy", "cases", "retention", "success", "pass^k", "tokens/trial", "cost/trial",
                "cache ratio", "unexpected cache breaks"), sr);
        d.h("Cache ratio per turn");
        d.svg(CacheChart.svg(s.path("strategies")));

        d.h("Most common failure modes");
        List<List<String>> fr = new ArrayList<>();
        for (JsonNode f : s.path("failureModes"))
            fr.add(List.of(f.path("count").asText(), f.path("check").asText(), f.path("reason").asText()));
        d.table(List.of("count", "check", "reason"), fr);

        JsonNode b = s.path("baseline");
        d.h("Baseline");
        if (!b.path("present").asBoolean()) {
            d.p("No baseline for this model yet" + (b.path("updated").asBoolean() ? "; this run was stored as baseline."
                    : ". Rerun with the update option to store this run."));
        } else {
            d.p("Compared with baseline run " + b.path("baselineRun").asText() + ": overall pass rate "
                    + signed(b.path("overallDelta")) + ", tokens/trial " + signed(b.path("tokensPerTrialDelta"))
                    + ", mean latency ms " + signed(b.path("meanMsDelta"))
                    + (b.path("updated").asBoolean() ? ". Baseline updated to this run." : "."));
            List<List<String>> br = new ArrayList<>();
            for (JsonNode x : b.path("regressions"))
                br.add(List.of("regression", x.path("case").asText(), pct(x.path("was")), pct(x.path("now"))));
            for (JsonNode x : b.path("improvements"))
                br.add(List.of("improvement", x.path("case").asText(), pct(x.path("was")), pct(x.path("now"))));
            for (JsonNode x : b.path("withinNoise"))
                br.add(List.of("within noise", x.path("case").asText(), pct(x.path("was")), pct(x.path("now"))));
            d.table(List.of("change", "case", "baseline", "now"), br);
            d.p("Regressions and improvements are reported only when the 95% Wilson intervals of both runs do not "
                    + "overlap; \"within noise\" rows changed but the trial counts cannot tell them apart from chance.");
        }
        return d;
    }

    static String judge(JsonNode run) {
        String m = run.path("judgeModel").asText();
        String t = run.path("judgeType").asText();
        return (t.isEmpty() ? "" : t + " ") + (m.isEmpty() ? "unknown" : m);
    }

    static String pct(JsonNode n) {
        return n.isNull() || n.isMissingNode() ? "n/a" : String.format(Locale.ROOT, "%.1f%%", n.asDouble() * 100);
    }

    static String num(JsonNode n) {
        return String.format(Locale.ROOT, "%.0f", n.asDouble());
    }

    static String money(JsonNode n) {
        return n.has("costPerTrial") ? String.format(Locale.ROOT, "$%.4f", n.path("costPerTrial").asDouble()) : "n/a";
    }

    static String lat(JsonNode l) {
        return String.format(Locale.ROOT, "%.0f / %.0f / %.0f", l.path("meanMs").asDouble(), l.path("p50Ms").asDouble(),
                l.path("p95Ms").asDouble());
    }

    private static String signed(JsonNode n) {
        return String.format(Locale.ROOT, "%+.3f", n.asDouble());
    }

    static void write(Path file, JsonNode node) {
        try {
            writeText(file, OUT.writeValueAsString(node));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeText(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Secrets.mask(text), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

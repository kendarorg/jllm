package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.eval.report.ResultLoader.CaseRun;
import org.kendar.llm.eval.report.ResultLoader.Check;
import org.kendar.llm.eval.report.ResultLoader.Req;
import org.kendar.llm.eval.report.ResultLoader.Run;
import org.kendar.llm.eval.report.ResultLoader.Trial;
import org.kendar.llm.harness.HarnessPricing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the machine-readable summary of one run. Check names are matched by substring: "retention"/"recall" for
 * retention, "cache" (failed or warned) for unexpected cache breaks.
 */
final class RunStats {

    private RunStats() {
    }

    /** Accumulator over cases and trials. */
    private static final class Agg {
        int cases, passKCases, trials, passedTrials, retentionChecks, retentionPassed, cacheBreaks;
        double rateSum;
        long in, out, cacheRead, cacheWrite, reqMillisSum, reqCount;
        final List<Long> millis = new ArrayList<>();
        final Map<Integer, double[]> turn = new TreeMap<>();
        HarnessPricing pricing;
        double cost;

        void add(CaseRun c) {
            cases++;
            rateSum += c.passRate();
            if (c.passK()) passKCases++;
            for (Trial t : c.trials()) {
                trials++;
                if (t.passed()) passedTrials++;
                millis.add(t.millis());
                long ti = 0, to = 0, tr = 0, tw = 0;
                int idx = 0;
                for (Req r : t.requests()) {
                    ti += r.input();
                    to += r.output();
                    tr += r.cacheRead();
                    tw += r.cacheWrite();
                    reqMillisSum += r.millis();
                    reqCount++;
                    if (!r.simple()) {
                        double[] a = turn.computeIfAbsent(idx, k -> new double[2]);
                        a[0] += r.cacheRatio();
                        a[1]++;
                        idx++;
                    }
                }
                in += ti;
                out += to;
                cacheRead += tr;
                cacheWrite += tw;
                if (pricing != null) cost += pricing.estimate(ti, to, tr, tw);
                for (Check k : t.checks()) {
                    String n = k.name().toLowerCase(Locale.ROOT);
                    if (n.contains("retention") || n.contains("recall") || n.startsWith("retained")) {
                        retentionChecks++;
                        if ("PASS".equals(k.status())) retentionPassed++;
                    }
                    if ((n.contains("cache") || n.startsWith("prefixstable") || n.startsWith("breaks")) && ("FAIL".equals(k.status()) || "WARN".equals(k.status()))) cacheBreaks++;
                }
            }
        }

        ObjectNode toJson(boolean withTurns) {
            ObjectNode o = ResultLoader.MAPPER.createObjectNode();
            o.put("cases", cases);
            o.put("trials", trials);
            o.put("passRate", cases == 0 ? 0 : rateSum / cases);
            o.put("passKRate", cases == 0 ? 0 : (double) passKCases / cases);
            if (retentionChecks > 0) o.put("retention", (double) retentionPassed / retentionChecks);
            else o.putNull("retention");
            ObjectNode tok = o.putObject("tokens");
            tok.put("input", in);
            tok.put("output", out);
            tok.put("cacheRead", cacheRead);
            tok.put("cacheWrite", cacheWrite);
            tok.put("perTrial", trials == 0 ? 0 : (double) (in + out + cacheRead + cacheWrite) / trials);
            if (pricing != null) {
                o.put("cost", cost);
                o.put("costPerTrial", trials == 0 ? 0 : cost / trials);
            }
            long ctx = in + cacheRead + cacheWrite;
            o.put("cacheRatio", ctx == 0 ? 0 : (double) cacheRead / ctx);
            o.put("cacheBreaks", cacheBreaks);
            ObjectNode lat = o.putObject("latency");
            List<Long> s = new ArrayList<>(millis);
            Collections.sort(s);
            lat.put("meanMs", s.isEmpty() ? 0 : s.stream().mapToLong(Long::longValue).average().orElse(0));
            lat.put("p50Ms", percentile(s, 0.5));
            lat.put("p95Ms", percentile(s, 0.95));
            lat.put("meanRequestMs", reqCount == 0 ? 0 : (double) reqMillisSum / reqCount);
            if (withTurns) {
                ArrayNode a = o.putArray("turnCacheRatio");
                turn.values().forEach(v -> a.add(v[0] / v[1]));
            }
            return o;
        }
    }

    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** @param pricing null or unpriced pricing leaves money out */
    static ObjectNode summarize(Run run, HarnessPricing pricing, boolean priced) {
        HarnessPricing p = priced ? pricing : null;
        ObjectNode root = ResultLoader.MAPPER.createObjectNode();
        ObjectNode r = root.putObject("run");
        r.put("model", run.model());
        r.put("judgeType", run.judgeType());
        r.put("judgeModel", run.judgeModel());
        r.put("selfGraded", run.selfGraded());
        r.put("trials", run.meta().path("trials").asInt());
        r.put("startTime", ResultLoader.text(run.meta(), "startTime"));
        r.put("runFolder", String.valueOf(run.dir().getFileName()));

        Agg all = new Agg();
        all.pricing = p;
        Map<String, Agg> features = new TreeMap<>();
        Map<String, Agg> strategies = new TreeMap<>();
        Map<String, int[]> failures = new LinkedHashMap<>();
        int missing = 0;
        for (CaseRun c : run.cases()) {
            Agg f = features.computeIfAbsent(c.feature(), k -> new Agg());
            Agg s = strategies.computeIfAbsent(c.strategy(), k -> new Agg());
            f.pricing = p;
            s.pricing = p;
            all.add(c);
            f.add(c);
            s.add(c);
            for (Trial t : c.trials()) {
                if (!t.traceLoaded()) missing++;
                for (Check k : t.checks()) {
                    if (!k.failed()) continue;
                    failures.computeIfAbsent(k.name() + "\u0000" + normalize(k.reason()), x -> new int[1])[0]++;
                }
            }
        }
        root.set("overall", all.toJson(false));
        ObjectNode fo = root.putObject("features");
        features.forEach((k, v) -> fo.set(k, v.toJson(false)));
        ObjectNode so = root.putObject("strategies");
        strategies.forEach((k, v) -> so.set(k, v.toJson(true)));

        ArrayNode fm = root.putArray("failureModes");
        failures.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(10).forEach(e -> {
            String[] kv = e.getKey().split("\u0000", -1);
            ObjectNode n = fm.addObject();
            n.put("check", kv[0]);
            n.put("reason", kv[1]);
            n.put("count", e.getValue()[0]);
        });

        ArrayNode cases = root.putArray("cases");
        for (CaseRun c : run.cases()) {
            ObjectNode n = cases.addObject();
            n.put("key", c.key());
            n.put("passRate", c.passRate());
            n.put("passK", c.passK());
            n.put("passed", c.passed());
        }
        ArrayNode warnings = root.putArray("warnings");
        if (run.selfGraded()) warnings.add("self-graded: the judge is the model under test");
        if (missing > 0) warnings.add(missing + " trial trace(s) missing: tokens and latency incomplete");
        if (run.cases().isEmpty()) warnings.add("no result.json found");
        return root;
    }

    /** Collapses digits and long text so equal failure modes group together. */
    static String normalize(String reason) {
        if (reason == null) return "";
        String s = reason.replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}

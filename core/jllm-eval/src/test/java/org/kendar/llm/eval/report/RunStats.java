package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
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
        int cases, trials, passedTrials, retentionChecks, retentionPassed, cacheBreaks;
        double rateSum;
        final List<Integer> ks;
        /** Per k: sum of the case estimates, and the number of cases with enough trials (the coverage). */
        final Map<Integer, double[]> atK = new TreeMap<>();
        final Map<Integer, double[]> powK = new TreeMap<>();
        long in, out, cacheRead, cacheWrite, reqMillisSum, reqCount;
        final List<Long> millis = new ArrayList<>();
        final Map<Integer, double[]> turn = new TreeMap<>();
        HarnessPricing pricing;
        double cost;

        Agg(List<Integer> ks) {
            this.ks = ks;
        }

        void add(CaseRun c) {
            cases++;
            rateSum += c.passRate();
            for (int k : ks) {
                PassAtK.passAt(c.n(), c.c(), k).ifPresent(v -> add(atK, k, v));
                PassAtK.passPow(c.n(), c.c(), k).ifPresent(v -> add(powK, k, v));
            }
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

        private static void add(Map<Integer, double[]> m, int k, double v) {
            double[] a = m.computeIfAbsent(k, x -> new double[2]);
            a[0] += v;
            a[1]++;
        }

        /** Macro average over the cases that have at least k trials; a k no case can tell is left out. */
        private void putKs(ObjectNode o) {
            ObjectNode at = o.putObject("passAtK");
            ObjectNode pow = o.putObject("passPowK");
            ObjectNode cov = o.putObject("coverage");
            for (int k : ks) {
                double[] a = atK.get(k);
                double[] p = powK.get(k);
                if (a != null) at.put(String.valueOf(k), a[0] / a[1]);
                if (p != null) pow.put(String.valueOf(k), p[0] / p[1]);
                cov.put(String.valueOf(k), a == null ? 0 : (int) a[1]);
            }
        }

        ObjectNode toJson(boolean withTurns) {
            ObjectNode o = ResultLoader.MAPPER.createObjectNode();
            o.put("cases", cases);
            o.put("trials", trials);
            o.put("passRate", cases == 0 ? 0 : rateSum / cases);
            putKs(o);
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
        List<Integer> ks = run.passK();
        ArrayNode kn = r.putArray("passK");
        ks.forEach(kn::add);
        r.put("reportK", ks.get(ks.size() - 1));
        JsonNode build = run.meta().path("build");
        r.put("commit", ResultLoader.text(build, "commit"));
        r.put("dirty", run.dirty());
        r.put("dirtyFileCount", build.path("dirtyFileCount").asInt());
        r.put("dirtyTree", run.dirtyTreePolicy());
        r.put("version", ResultLoader.text(build, "projectVersion"));
        r.put("settingsHash", ResultLoader.text(run.meta(), "settingsHash"));
        r.put("promptsHash", ResultLoader.text(run.meta(), "promptsHash"));
        r.put("startTime", ResultLoader.text(run.meta(), "startTime"));
        r.put("runFolder", String.valueOf(run.dir().getFileName()));

        Agg all = new Agg(ks);
        all.pricing = p;
        Map<String, Agg> features = new TreeMap<>();
        Map<String, Agg> strategies = new TreeMap<>();
        Map<String, int[]> failures = new LinkedHashMap<>();
        int missing = 0;
        for (CaseRun c : run.cases()) {
            Agg f = features.computeIfAbsent(c.feature(), k -> new Agg(ks));
            Agg s = strategies.computeIfAbsent(c.strategy(), k -> new Agg(ks));
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
            ObjectNode at = n.putObject("passAtK");
            ObjectNode pow = n.putObject("passPowK");
            for (int k : ks) {
                PassAtK.passAt(c.n(), c.c(), k).ifPresent(v -> at.put(String.valueOf(k), v));
                PassAtK.passPow(c.n(), c.c(), k).ifPresent(v -> pow.put(String.valueOf(k), v));
            }
            n.put("passed", c.passed());
            n.put("trials", c.trials().size());
            n.put("passes", (int) c.trials().stream().filter(Trial::passed).count());
        }
        ArrayNode warnings = root.putArray("warnings");
        if (run.dirty() && !"allow".equals(run.dirtyTreePolicy())) {
            warnings.add("dirty working tree: " + build.path("dirtyFileCount").asInt()
                    + " tracked file(s) differed from commit " + ResultLoader.text(build, "commit")
                    + " during the run; the results do not match a commit");
        }
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

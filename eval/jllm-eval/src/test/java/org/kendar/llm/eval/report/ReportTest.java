package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReportTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;

    /** passes: per-trial verdicts; the failing ones carry a failed check with the given name. */
    private void writeCase(Path run, String feature, String name, String strategy, String rubric, boolean[] passes,
                           String failCheck, String failReason, long cacheRead, long input) throws IOException {
        Path dir = run.resolve(feature).resolve(name).resolve(strategy);
        Files.createDirectories(dir);
        ObjectNode doc = M.createObjectNode();
        doc.put("case", name);
        doc.put("feature", feature);
        doc.put("tag", "FULL");
        doc.put("strategy", strategy);
        doc.put("risk", false);
        if (rubric == null) doc.putNull("rubric");
        else doc.put("rubric", rubric);
        long ok = 0;
        for (boolean b : passes) if (b) ok++;
        doc.put("passRate", (double) ok / passes.length);
        ObjectNode at = doc.putObject("passAtK");
        ObjectNode pow = doc.putObject("passPowK");
        for (int k = 1; k <= 3; k++) {
            int kk = k;
            PassAtK.passAt(passes.length, (int) ok, k).ifPresent(v -> at.put(String.valueOf(kk), v));
            PassAtK.passPow(passes.length, (int) ok, k).ifPresent(v -> pow.put(String.valueOf(kk), v));
        }
        doc.put("passed", ok == passes.length);
        ArrayNode trials = doc.putArray("trials");
        for (int i = 0; i < passes.length; i++) {
            int n = i + 1;
            ObjectNode t = trials.addObject();
            t.put("trial", n);
            t.put("passed", passes[i]);
            ArrayNode checks = t.putArray("checks");
            checks.addObject().put("name", "tool-called").put("status", "PASS").put("reason", "ok");
            if (rubric != null)
                checks.addObject().put("name", "judge:" + rubric).put("status", passes[i] ? "PASS" : "FAIL")
                        .put("reason", "judge said so");
            else if (passes[i] && failCheck != null)
                checks.addObject().put("name", failCheck).put("status", "PASS").put("reason", "fine");
            else if (!passes[i])
                checks.addObject().put("name", failCheck).put("status", "FAIL").put("reason", failReason + " " + n);
            t.put("millis", 1000L * n);
            t.put("requests", 2);
            t.put("trace", dir.resolve("trial-" + n + ".json").toString());

            ObjectNode tr = M.createObjectNode();
            tr.put("trial", n);
            tr.put("finalAnswer", "answer " + n);
            // two identical calls (one repeated) per trial, a stuck event in the first trial
            ArrayNode events = tr.putArray("events");
            for (int k = 0; k < 2; k++) {
                ObjectNode e = events.addObject().put("type", "tool_call");
                e.putObject("data").put("name", "read_file").putObject("input").put("path", "a.txt");
            }
            if (n == 1) events.addObject().put("type", "stuck").putObject("data").put("action", "nudge");
            ArrayNode reqs = tr.putArray("requests");
            for (int k = 0; k < 2; k++) {
                ObjectNode r = reqs.addObject();
                r.put("index", k);
                r.put("simple", false);
                r.put("millis", 100);
                ObjectNode u = r.putObject("usage");
                u.put("inputTokens", input);
                u.put("outputTokens", 10);
                u.put("cacheReadTokens", k == 0 ? 0 : cacheRead);
                u.put("cacheCreationTokens", 0);
            }
            Files.writeString(dir.resolve("trial-" + n + ".json"), M.writeValueAsString(tr));
        }
        Files.writeString(dir.resolve("result.json"), M.writeValueAsString(doc));
    }

    private Path synthetic(String name, String model, String judge, boolean self, boolean[] failing) throws IOException {
        return synthetic(name, model, judge, self, failing, false, "warn");
    }

    private Path synthetic(String name, String model, String judge, boolean self, boolean[] failing, boolean dirty,
                           String dirtyPolicy) throws IOException {
        Path run = tmp.resolve(name);
        Files.createDirectories(run);
        ObjectNode meta = M.createObjectNode();
        meta.put("targetModel", model);
        meta.put("judgeType", "anthropic");
        meta.put("judgeModel", judge);
        meta.put("selfGraded", self);
        meta.put("trials", 3);
        meta.put("startTime", "2026-01-01T00:00:00Z");
        meta.putArray("passK").add(1).add(2).add(3);
        ObjectNode build = meta.putObject("build");
        build.put("commit", "0123456789abcdef");
        build.put("dirty", dirty);
        build.put("dirtyFileCount", dirty ? 2 : 0);
        build.put("projectVersion", "1.2.3");
        meta.put("settingsHash", "ab12cd34ef56ab12cd34ef56");
        meta.put("promptsHash", "99aa88bb77cc99aa88bb77cc");
        meta.put("dirtyTree", dirtyPolicy);
        Files.writeString(run.resolve("run.json"), M.writeValueAsString(meta));
        writeCase(run, "files", "read", "compaction", null, failing, "tool-called", "no tool call", 80, 20);
        writeCase(run, "files", "read", "mnemonic", null, new boolean[]{true, true, true}, null, null, 90, 10);
        writeCase(run, "context", "retention-case", "compaction", null, new boolean[]{true, false, false},
                "retention-fact", "lost fact 42", 0, 100);
        writeCase(run, "summary", "sum", "scope", "quality", new boolean[]{true, true, false}, null, null, 50, 50);
        return run;
    }

    @Test
    void loaderReadsRun() throws IOException {
        Path run = synthetic("r1", "m1", "j1", false, new boolean[]{true, true, false});
        ResultLoader.Run r = ResultLoader.load(run);
        assertEquals("m1", r.model());
        assertEquals(4, r.cases().size());
        ResultLoader.CaseRun c = r.cases().get(0);
        assertEquals(3, c.trials().size());
        assertTrue(c.trials().get(0).traceLoaded());
        assertEquals(2, c.trials().get(0).requests().size());
    }

    @Test
    void buildsSummaryAndReports() throws IOException {
        Path run = synthetic("r1", "m1", "j1", false, new boolean[]{true, true, false});
        JsonNode s = ReportBuilder.build(run, false);
        assertTrue(Files.isRegularFile(run.resolve("summary.json")));
        assertTrue(Files.isRegularFile(run.resolve("report.md")));
        String html = Files.readString(run.resolve("report.html"));
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("m1"));
        assertFalse(s.path("overall").has("cost"), "no price, no money");
        JsonNode comp = s.path("strategies").path("compaction");
        assertEquals(2, comp.path("cases").asInt());
        // compaction: case read 2/3, retention-case 1/3 -> 0.5
        assertEquals(0.5, comp.path("passRate").asDouble(), 1e-9);
        // pass@k / pass^k: case read has 2 of 3, retention-case 1 of 3, macro average over the 2 cases
        assertEquals(0.5, comp.path("passAtK").path("1").asDouble(), 1e-9);
        assertEquals(0.5, comp.path("passPowK").path("1").asDouble(), 1e-9);
        assertEquals((1.0 + 2.0 / 3) / 2, comp.path("passAtK").path("2").asDouble(), 1e-9);
        assertEquals((1.0 / 3 + 0.0) / 2, comp.path("passPowK").path("2").asDouble(), 1e-9);
        assertEquals(1.0, comp.path("passAtK").path("3").asDouble(), 1e-9);
        assertEquals(0.0, comp.path("passPowK").path("3").asDouble(), 1e-9);
        assertEquals(2, comp.path("coverage").path("3").asInt());
        assertEquals(1.0 / 3, comp.path("retention").asDouble(), 1e-9);
        assertEquals(2, comp.path("turnCacheRatio").size());
        assertEquals(0.0, comp.path("turnCacheRatio").get(0).asDouble(), 1e-9);
        assertEquals(0.4, comp.path("turnCacheRatio").get(1).asDouble(), 1e-9);
        assertTrue(s.path("strategies").path("mnemonic").path("retention").isNull());
        assertEquals(5.0 / 6, s.path("features").path("files").path("passRate").asDouble(), 1e-9);
        JsonNode fm = s.path("failureModes");
        assertEquals(2, fm.get(0).path("count").asInt());
        assertEquals("retention-fact", fm.get(0).path("check").asText());
        assertEquals("lost fact # #", fm.get(0).path("reason").asText());
        assertTrue(s.path("overall").path("latency").path("p95Ms").asLong() >= 3000);
        assertFalse(s.path("baseline").path("present").asBoolean());
    }

    @Test
    void processMetricsAndFlakyCases() throws IOException {
        Path run = synthetic("r1", "m1", "j1", false, new boolean[]{true, true, false});
        JsonNode s = ReportBuilder.build(run, false);
        JsonNode p = s.path("overall").path("process");
        assertEquals(12, p.path("trials").asInt());
        assertEquals(2.0, p.path("toolCallsPerTrial").asDouble(), 1e-9);
        assertEquals(1.0, p.path("duplicateCallsPerTrial").asDouble(), 1e-9);
        assertEquals(4.0 / 12, p.path("stuckEventsPerTrial").asDouble(), 1e-9);
        // mixed trials: files/read/compaction, retention-case, sum; files/read/mnemonic passed all
        assertEquals(3, s.path("overall").path("flakyCases").asInt());
        JsonNode first = s.path("cases").get(0);
        assertEquals(2.0, first.path("toolCallsPerTrial").asDouble(), 1e-9);
        String md = Files.readString(run.resolve("report.md"));
        assertTrue(md.contains("## Process") || md.contains("Process"), md);
        assertTrue(md.contains("flaky case"), md);
    }

    @Test
    void pricingAddsMoney() throws IOException {
        Path run = synthetic("r1", "m1", "j1", false, new boolean[]{true, true, true});
        System.setProperty("eval.price.input", "1000000");
        try {
            JsonNode s = ReportBuilder.build(run, false);
            assertTrue(s.path("overall").path("cost").asDouble() > 0);
        } finally {
            System.clearProperty("eval.price.input");
        }
    }

    @Test
    void baselineUpdateAndDiff() throws IOException {
        Path r1 = synthetic("r1", "m1", "j1", false, new boolean[]{true, true, true, true, true, true, true, true, true, true});
        JsonNode first = ReportBuilder.build(r1, true);
        assertTrue(first.path("baseline").path("updated").asBoolean());
        assertTrue(Files.isRegularFile(tmp.resolve("baselines").resolve("m1.json")));

        Path r2 = synthetic("r2", "m1", "j1", false, new boolean[]{true, false, false, false, false, false, false, false, false, true});
        JsonNode second = ReportBuilder.build(r2, false);
        JsonNode b = second.path("baseline");
        assertTrue(b.path("present").asBoolean());
        assertEquals(1, b.path("regressions").size());
        assertEquals("files/read/compaction", b.path("regressions").get(0).path("case").asText());
        assertTrue(b.path("overallDelta").asDouble() < 0);
        assertFalse(b.path("updated").asBoolean());
        // not updated without the option
        assertEquals("r1", M.readTree(tmp.resolve("baselines").resolve("m1.json").toFile()).path("runFolder").asText());
    }

    @Test
    void mergeWarnsOnDifferentJudges() throws IOException {
        Path a = synthetic("a", "m1", "j1", false, new boolean[]{true, true, true});
        Path b = synthetic("b", "m2", "j2", true, new boolean[]{true, false, true});
        Path out = tmp.resolve("merged");
        JsonNode m = ReportMerger.merge(List.of(a, b), out);
        assertEquals(2, m.path("runs").size());
        assertTrue(m.path("warnings").toString().contains("different judges"));
        assertTrue(m.path("warnings").toString().contains("self-graded"));
        String md = Files.readString(out.resolve("merged.md"));
        assertTrue(md.contains("| metric | m1 | m2 |"));
        assertTrue(md.contains("judge"));
        assertTrue(Files.isRegularFile(out.resolve("merged.html")));

        Path same = synthetic("c", "m3", "j1", false, new boolean[]{true, true, true});
        JsonNode m2 = ReportMerger.merge(List.of(a, same), tmp.resolve("merged2"));
        assertEquals(0, m2.path("warnings").size());
    }

    private void setPassK(Path run, int... ks) throws IOException {
        ObjectNode meta = (ObjectNode) M.readTree(run.resolve("run.json").toFile());
        ArrayNode a = meta.putArray("passK");
        for (int k : ks) a.add(k);
        Files.writeString(run.resolve("run.json"), M.writeValueAsString(meta));
    }

    @Test
    void reportShowsTheLargestKWithItsHeaderAndAReliabilityTable() throws IOException {
        Path run = synthetic("r1", "m1", "j1", false, new boolean[]{true, true, false});
        JsonNode s = ReportBuilder.build(run, false);
        assertEquals(3, s.path("run").path("reportK").asInt());
        String md = Files.readString(run.resolve("report.md"));
        assertTrue(md.contains("pass@3") && md.contains("pass^3"), md);
        assertTrue(md.contains("Reliability by k"), md);
        assertTrue(md.contains("unbiased"), md);
        assertFalse(md.contains("| k greater than trials"), md);
        assertTrue(md.contains("0123456789abcdef".substring(0, 8)) && md.contains("1.2.3") && md.contains("ab12cd34ef56"), md);
    }

    @Test
    void partialCoverageIsShownAndAKAboveEveryCasesTrialsIsNotMeasured() throws IOException {
        Path run = tmp.resolve("cov");
        Files.createDirectories(run);
        Files.writeString(run.resolve("run.json"), "{\"targetModel\":\"m\",\"passK\":[1,3],\"trials\":3}");
        writeCase(run, "f", "long", "scope", null, new boolean[]{true, true, false}, "x", "bad", 0, 1);
        writeCase(run, "f", "short", "scope", null, new boolean[]{true, true}, null, null, 0, 1);
        JsonNode s = ReportBuilder.build(run, false);
        JsonNode ov = s.path("overall");
        assertEquals(1, ov.path("coverage").path("3").asInt());
        assertEquals(2, ov.path("coverage").path("1").asInt());
        assertEquals(1.0, ov.path("passAtK").path("3").asDouble(), 1e-9);
        assertEquals(0.0, ov.path("passPowK").path("3").asDouble(), 1e-9);
        assertTrue(Files.readString(run.resolve("report.md")).contains("(1 of 2 cases)"));

        Path none = tmp.resolve("none");
        Files.createDirectories(none);
        Files.writeString(none.resolve("run.json"), "{\"targetModel\":\"m\",\"passK\":[1,3],\"trials\":2}");
        writeCase(none, "f", "a", "scope", null, new boolean[]{true, false}, "x", "bad", 0, 1);
        JsonNode n = ReportBuilder.build(none, false);
        assertFalse(n.path("overall").path("passAtK").has("3"));
        assertEquals(0, n.path("overall").path("coverage").path("3").asInt());
        assertTrue(Files.readString(none.resolve("report.md")).contains("| k greater than trials: not measured |"));
    }

    @Test
    void oldFormatFoldersFailClearly() throws IOException {
        Path run = synthetic("old", "m1", "j1", false, new boolean[]{true, true, true});
        Path result = run.resolve("files/read/compaction/result.json");
        ObjectNode doc = (ObjectNode) M.readTree(result.toFile());
        doc.remove("passAtK");
        doc.remove("passPowK");
        doc.put("passK", true);
        Files.writeString(result, M.writeValueAsString(doc));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ResultLoader.load(run));
        assertTrue(e.getMessage().contains("old result format"), e.getMessage());

        Path noKs = synthetic("old2", "m1", "j1", false, new boolean[]{true, true, true});
        ObjectNode meta = (ObjectNode) M.readTree(noKs.resolve("run.json").toFile());
        meta.remove("passK");
        Files.writeString(noKs.resolve("run.json"), M.writeValueAsString(meta));
        IllegalStateException e2 = assertThrows(IllegalStateException.class, () -> ReportBuilder.build(noKs, false));
        assertTrue(e2.getMessage().contains("old result format"), e2.getMessage());
    }

    @Test
    void aDirtyTreeIsWarnedInTheSummaryAndTheReport() throws IOException {
        Path run = synthetic("d1", "m1", "j1", false, new boolean[]{true, true, true}, true, "warn");
        JsonNode s = ReportBuilder.build(run, false);
        assertTrue(s.path("warnings").toString().contains("dirty working tree"), s.path("warnings").toString());
        assertTrue(s.path("run").path("dirty").asBoolean());
        String md = Files.readString(run.resolve("report.md"));
        assertTrue(md.contains("dirty working tree") && md.contains("(DIRTY)"), md);

        Path allowed = synthetic("d2", "m1", "j1", false, new boolean[]{true, true, true}, true, "allow");
        assertFalse(ReportBuilder.build(allowed, false).path("warnings").toString().contains("dirty working tree"));

        Path clean = synthetic("d3", "m1", "j1", false, new boolean[]{true, true, true});
        assertEquals(0, ReportBuilder.build(clean, false).path("warnings").size());
    }

    @Test
    void aBaselineIsNeverStoredFromADirtyTree() throws IOException {
        Path run = synthetic("d1", "m1", "j1", false, new boolean[]{true, true, true}, true, "allow");
        assertThrows(IllegalStateException.class, () -> ReportBuilder.build(run, true));
        assertFalse(Files.exists(tmp.resolve("baselines").resolve("m1.json")));
    }

    @Test
    void theBaselineStoresTheKMaps() throws IOException {
        Path run = synthetic("b1", "m1", "j1", false, new boolean[]{true, true, false});
        ReportBuilder.build(run, true);
        JsonNode base = M.readTree(tmp.resolve("baselines").resolve("m1.json").toFile());
        assertTrue(base.path("overall").path("passAtK").has("3"));
        assertTrue(base.path("overall").path("passPowK").has("2"));
        assertTrue(base.path("cases").path("files/read/compaction").path("passPowK").has("1"));
    }

    @Test
    void mergerShowsCommitAndTheKOfEachRunWhenTheyDiffer() throws IOException {
        Path a = synthetic("a", "m1", "j1", false, new boolean[]{true, true, true});
        Path same = synthetic("b", "m2", "j1", false, new boolean[]{true, true, true});
        ReportMerger.merge(List.of(a, same), tmp.resolve("same"));
        String md = Files.readString(tmp.resolve("same").resolve("merged.md"));
        assertTrue(md.contains("| commit | 01234567 | 01234567 |"), md);
        assertTrue(md.contains("| pass@3 |") && md.contains("| pass^3 |"), md);

        Path c = synthetic("c", "m3", "j1", false, new boolean[]{true, true, true});
        setPassK(c, 1, 2);
        ReportMerger.merge(List.of(a, c), tmp.resolve("diff"));
        String md2 = Files.readString(tmp.resolve("diff").resolve("merged.md"));
        assertTrue(md2.contains("| pass@k |") && md2.contains("(k=3)") && md2.contains("(k=2)"), md2);
    }

    @Test
    void labelExportIsBalancedAndAgreementFlags() throws IOException {
        Path run = tmp.resolve("lab");
        Files.createDirectories(run);
        // two rubrics, 30 trials each side overall
        writeCase(run, "f", "c1", "scope", "alpha", alt(12, true), null, null, 0, 1);
        writeCase(run, "f", "c2", "scope", "alpha", alt(12, false), null, null, 0, 1);
        writeCase(run, "f", "c3", "scope", "beta", alt(12, true), null, null, 0, 1);
        writeCase(run, "f", "c4", "scope", "beta", alt(12, false), null, null, 0, 1);
        Path csv = tmp.resolve("labels.csv");
        List<LabelExport.Row> rows = LabelExport.export(run, csv, 40);
        assertEquals(40, rows.size());
        assertEquals(20, rows.stream().filter(r -> r.judgeVerdict().equals("pass")).count());
        assertEquals(20, rows.stream().filter(r -> r.rubric().equals("alpha")).count());
        assertEquals(rows.size(), LabelExport.read(csv).size());

        // human agrees with judge on beta; on alpha disagrees on 5 of 20 (judge-pass, human-fail)
        List<LabelExport.Row> labeled = new java.util.ArrayList<>();
        int flips = 0;
        for (LabelExport.Row r : rows) {
            String h = r.judgeVerdict();
            if (r.rubric().equals("alpha") && r.judgeVerdict().equals("pass") && flips < 5) {
                h = "fail";
                flips++;
            }
            labeled.add(new LabelExport.Row(r.id(), r.feature(), r.caseName(), r.strategy(), r.trial(), r.rubric(),
                    r.judgeVerdict(), r.trace(), r.finalAnswer(), h));
        }
        Path json = tmp.resolve("labeled.json");
        LabelExport.write(json, labeled);
        List<Agreement.RubricAgreement> ag = Agreement.compute(json);
        assertEquals(2, ag.size());
        Agreement.RubricAgreement alpha = ag.get(0);
        assertEquals("alpha", alpha.rubric());
        assertEquals(0.75, alpha.agreement(), 1e-9);
        assertTrue(alpha.falsePassRate() > 0.05);
        assertTrue(alpha.flagged());
        Agreement.RubricAgreement beta = ag.get(1);
        assertEquals(1.0, beta.agreement(), 1e-9);
        assertFalse(beta.flagged());
        assertTrue(Agreement.format(ag).contains("FLAGGED"));
    }

    @Test
    void unlabeledRowsAreIgnored() throws IOException {
        Path run = tmp.resolve("lab2");
        Files.createDirectories(run);
        writeCase(run, "f", "c1", "scope", "alpha", alt(4, true), null, null, 0, 1);
        Path csv = tmp.resolve("l.csv");
        LabelExport.export(run, csv, 40);
        assertEquals(4, LabelExport.read(csv).size());
        assertTrue(Agreement.compute(csv).isEmpty());
    }

    private static boolean[] alt(int n, boolean pass) {
        boolean[] b = new boolean[n];
        java.util.Arrays.fill(b, pass);
        return b;
    }
}

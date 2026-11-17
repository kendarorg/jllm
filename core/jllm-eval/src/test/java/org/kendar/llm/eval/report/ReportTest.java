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
        doc.put("passK", ok == passes.length);
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
        Path run = tmp.resolve(name);
        Files.createDirectories(run);
        ObjectNode meta = M.createObjectNode();
        meta.put("targetModel", model);
        meta.put("judgeType", "anthropic");
        meta.put("judgeModel", judge);
        meta.put("selfGraded", self);
        meta.put("trials", 3);
        meta.put("startTime", "2026-01-01T00:00:00Z");
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
        assertEquals(0.0, comp.path("passKRate").asDouble(), 1e-9);
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
        Path r1 = synthetic("r1", "m1", "j1", false, new boolean[]{true, true, true});
        JsonNode first = ReportBuilder.build(r1, true);
        assertTrue(first.path("baseline").path("updated").asBoolean());
        assertTrue(Files.isRegularFile(tmp.resolve("baselines").resolve("m1.json")));

        Path r2 = synthetic("r2", "m1", "j1", false, new boolean[]{true, false, false});
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

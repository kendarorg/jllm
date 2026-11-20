package org.kendar.llm.eval.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kendar.llm.eval.EvalLog;
import org.kendar.llm.eval.RunMetadata;
import org.kendar.llm.eval.EvalRun;
import org.kendar.llm.eval.run.TrialResult;
import org.kendar.llm.eval.run.TrialRunner;
import org.kendar.llm.harness.context.ContextStrategyKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the trials of a case one after another, saves the result and applies the thresholds. */
public final class CaseExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final TrialRunner runner;
    private final Path outputDir;
    private final int defaultTrials;
    private final int maxTrials;
    private final RunMetadata metadata;

    /**
     * @param metadata attached to every saved result; null in unit tests, whose cases then stay out of the run's
     *                 progress counters (they share the JVM with the eval suites)
     */
    public CaseExecutor(TrialRunner runner, Path outputDir, int defaultTrials, RunMetadata metadata) {
        this(runner, outputDir, defaultTrials, Integer.MAX_VALUE, metadata);
    }

    /** @param maxTrials upper bound on the trials of every case, also the ones that set their own count */
    public CaseExecutor(TrialRunner runner, Path outputDir, int defaultTrials, int maxTrials, RunMetadata metadata) {
        this.runner = runner;
        this.outputDir = outputDir;
        this.defaultTrials = defaultTrials;
        this.maxTrials = maxTrials;
        this.metadata = metadata;
    }

    /** Runs all trials of {@code c} under {@code strategy}. */
    public CaseResult run(EvalCase c, ContextStrategyKind strategy) {
        int n = Math.min(c.trials() != null ? c.trials() : defaultTrials, maxTrials);
        Path dir = outputDir.resolve(c.feature()).resolve(c.name()).resolve(strategy.name().toLowerCase());
        List<TrialResult> trials = new ArrayList<>();
        String label = c.feature() + "/" + c.name() + " [" + strategy.name().toLowerCase() + "]";
        long t0 = System.nanoTime();
        if (metadata != null) EvalLog.caseStarted(label, n);
        else EvalLog.info("unit case start " + label + " (" + n + " trial(s))");
        for (int i = 1; i <= n; i++) {
            long ts = System.nanoTime();
            TrialResult t = runner.run(c, strategy, i, dir);
            long tm = (System.nanoTime() - ts) / 1_000_000;
            EvalLog.info("  trial " + i + "/" + n + " " + (t.passed() ? "pass" : metadata != null ? "FAIL" : "fail")
                    + " " + label + " "
                    + EvalLog.seconds(tm) + "s, " + t.trace().requests().size() + " model request(s)"
                    + (t.passed() ? "" : ", failed: " + t.failedChecks().stream()
                    .map(k -> k.name() + " (" + k.reason() + ")").collect(java.util.stream.Collectors.joining("; "))));
            trials.add(t);
            // a risk case cannot recover from a failed trial: stop spending model calls
            if (c.risk() && !t.passed()) break;
        }
        CaseResult r = CaseResult.of(c, strategy.name().toLowerCase(), trials);
        save(dir, c, r);
        long millis = (System.nanoTime() - t0) / 1_000_000;
        String detail = "pass rate " + Math.round(r.passRate() * 100) + "%";
        if (metadata != null) EvalLog.caseFinished(label, r.passed(), millis, detail);
        else EvalLog.info("unit case " + (r.passed() ? "pass" : "fail") + " " + label + " in " + EvalLog.seconds(millis)
                + "s - " + detail);
        return r;
    }

    private void save(Path dir, EvalCase c, CaseResult r) {
        try {
            Files.createDirectories(dir);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("case", r.caseName());
            doc.put("feature", r.feature());
            doc.put("tag", c.tag());
            doc.put("strategy", r.strategy());
            doc.put("risk", r.risk());
            doc.put("rubric", c.rubric());
            doc.put("passRate", r.passRate());
            doc.put("passK", r.passK());
            doc.put("passed", r.passed());
            doc.put("trials", r.trials().stream().map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("trial", t.trial());
                m.put("passed", t.passed());
                m.put("checks", t.checks());
                m.put("millis", t.trace().millis());
                m.put("requests", t.trace().requests().size());
                m.put("trace", t.traceFile() == null ? null : t.traceFile().toString());
                return m;
            }).toList());
            doc.put("run", metadata);
            Files.writeString(dir.resolve("result.json"), EvalRun.activeSecrets().maskJson(MAPPER.writeValueAsString(doc)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package org.kendar.llm.eval.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kendar.llm.eval.RunMetadata;
import org.kendar.llm.eval.Secrets;
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

    /** @param metadata attached to every saved result; may be null in unit tests */
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
        for (int i = 1; i <= n; i++) {
            TrialResult t = runner.run(c, strategy, i, dir);
            trials.add(t);
            // a risk case cannot recover from a failed trial: stop spending model calls
            if (c.risk() && !t.passed()) break;
        }
        CaseResult r = CaseResult.of(c, strategy.name().toLowerCase(), trials);
        save(dir, c, r);
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
            Files.writeString(dir.resolve("result.json"), Secrets.mask(MAPPER.writeValueAsString(doc)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package org.kendar.llm.eval;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The run: configuration, local server, connection check and metadata, built once on first use and shared by every
 * test. A failed setup is remembered and rethrown to every test, so nothing runs against a half-ready environment.
 */
public final class EvalRun {

    private static EvalRun instance;
    private static RuntimeException failure;

    private final EvalConfig config;
    private final RunMetadata metadata;
    private final Path outputDir;
    private final Path metadataFile;

    private EvalRun(EvalConfig config, RunMetadata metadata, Path outputDir, Path metadataFile) {
        this.config = config;
        this.metadata = metadata;
        this.outputDir = outputDir;
        this.metadataFile = metadataFile;
    }

    public static synchronized EvalRun get() {
        if (failure != null) throw failure;
        if (instance == null) {
            try {
                instance = start();
            } catch (RuntimeException e) {
                failure = e;
                throw e;
            }
        }
        return instance;
    }

    private static EvalRun start() {
        Instant start = Instant.now();
        EvalLog.info("run starting: loading configuration");
        EvalConfig config = EvalConfig.load();
        EvalLog.info("target " + config.target().name() + " model " + config.target().model() + ", judge "
                + config.judge().model() + (config.selfGraded() ? " (self-graded)" : "") + ", trials "
                + config.trials() + " (max " + config.maxTrials() + "), max rpm " + config.target().maxRpm()
                + ", test classes in parallel: " + System.getProperty("run.parallelism", "1"));
        EvalLog.info("connection check and warm-up call (the first call can take minutes on a cold model)");
        ConnectionCheck.Result check = ConnectionCheck.run(config, EvalModels::client);
        RunMetadata meta = RunMetadata.of(config, check, start);
        Path dir = config.outputDir().resolve(slug(meta.targetModel())).resolve("run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(start));
        try {
            Path file = meta.write(dir);
            EvalRun run = new EvalRun(config, meta, dir, file);
            EvalLog.info("warm-up done " + check.warmupMillis() + " ms; results in " + dir);
            Runtime.getRuntime().addShutdownHook(new Thread(run::summarize, "eval-summary"));
            return run;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot write run metadata: " + Secrets.mask(e.getMessage()), e);
        }
    }

    /** Folder name of a model: lower case, anything but letters, digits, dot and dash replaced by a dash. */
    static String slug(String model) {
        String s = model.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9.-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "model" : s;
    }

    /**
     * Writes the report of this run and the cross-model summary (latest run of every model folder). Runs when the
     * JVM ends; a failure only prints a line, it never hides the test results.
     */
    void summarize() {
        try {
            if (!java.nio.file.Files.isDirectory(outputDir) || !hasResults(outputDir)) return;
            org.kendar.llm.eval.report.ReportBuilder.build(outputDir, false);
            Path history = org.kendar.llm.eval.report.History.record(outputDir, config.outputDir().resolve("history"));
            java.util.List<Path> latest = new java.util.ArrayList<>();
            try (var models = java.nio.file.Files.list(outputDir.getParent().getParent())) {
                for (Path m : (Iterable<Path>) models.filter(java.nio.file.Files::isDirectory).sorted()::iterator) {
                    try (var runs = java.nio.file.Files.list(m)) {
                        runs.filter(r -> r.getFileName().toString().startsWith("run-")
                                        && java.nio.file.Files.exists(r.resolve("summary.json")))
                                .max(java.util.Comparator.comparing(r -> r.getFileName().toString()))
                                .ifPresent(latest::add);
                    }
                }
            }
            Path summary = outputDir.getParent().getParent().resolve("summary");
            org.kendar.llm.eval.report.ReportMerger.merge(latest, summary);
            System.err.println("[jllm-eval] report: " + outputDir.resolve("report.md") + " ; summary: "
                    + summary.resolve("merged.md") + " ; history: " + history);
        } catch (Exception e) {
            System.err.println("[jllm-eval] summary not written: " + Secrets.mask(String.valueOf(e)));
        }
    }

    private static boolean hasResults(Path dir) throws java.io.IOException {
        try (var s = java.nio.file.Files.walk(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().equals("result.json"));
        }
    }

    public EvalConfig config() { return config; }
    public RunMetadata metadata() { return metadata; }
    public Path outputDir() { return outputDir; }
    public Path metadataFile() { return metadataFile; }
}

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

    private static volatile EvalRun instance;
    private static RuntimeException failure;

    private final EvalConfig config;
    private final RunMetadata metadata;
    private final Path outputDir;
    private final Path metadataFile;
    private final java.util.concurrent.atomic.AtomicBoolean summarized = new java.util.concurrent.atomic.AtomicBoolean();

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

    /**
     * The secrets of the started run, for what the run writes or logs (logs, traces, results, reports); none before
     * the run starts or outside one (unit tests). Never starts the run.
     */
    public static Secrets activeSecrets() {
        EvalRun run = instance;
        return run == null ? Secrets.NONE : run.config.secrets();
    }

    private static EvalRun start() {
        Instant start = Instant.now();
        EvalLog.info("run starting: loading configuration");
        EvalConfig config = EvalConfig.load();
        EvalLog.info("target " + config.target().name() + " model " + named(config.targetModelName(),
                config.target().model()) + ", judge " + named(config.judgeModelName(), config.judge().model())
                + (config.selfGraded() ? " (self-graded)" : "") + ", trials "
                + config.trials() + " (max " + config.maxTrials() + "), max rpm " + config.target().maxRpm()
                + ", test classes in parallel: " + System.getProperty("run.parallelism", "1"));
        EvalLog.info("connection check and warm-up call (the first call can take minutes on a cold model)");
        ConnectionCheck.Result check = ConnectionCheck.run(config, EvalModels::client);
        RunMetadata meta = RunMetadata.of(config, check, start);
        Path dir = config.outputDir().resolve(slug(meta.targetModel())).resolve("run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(start));
        try {
            Path file = meta.write(dir, config.secrets());
            EvalRun run = new EvalRun(config, meta, dir, file);
            EvalLog.info("warm-up done " + check.warmupMillis() + " ms; results in " + dir);
            // normally written by EvalProgressListener when the test plan ends; the hook covers a JVM that ends early
            Runtime.getRuntime().addShutdownHook(new Thread(run::summarize, "eval-summary"));
            return run;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot write run metadata: " + config.secrets().mask(e.getMessage()), e);
        }
    }

    /** {@code name (id id)} when the reported name differs from the id sent to the server, else the id. */
    private static String named(String name, String id) {
        return name.equals(id) ? id : name + " (id " + id + ")";
    }

    /** Folder name of a model: lower case, anything but letters, digits, dot and dash replaced by a dash. */
    static String slug(String model) {
        String s = model.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9.-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "model" : s;
    }

    /**
     * Writes the report of the started run, if any, when the test plan ends. Called by the JUnit listener: a JVM
     * shutdown hook alone is not enough, surefire ends the forked JVM before a long summary is written.
     */
    public static void finish() {
        EvalRun run = instance;
        if (run != null) run.summarize();
    }

    /**
     * Writes the report of this run and the cross-model summary (latest run of every model folder), once. Runs when
     * the test plan ends, or when the JVM ends if it never did; a failure only logs a line, it never hides the test
     * results.
     */
    void summarize() {
        if (!summarized.compareAndSet(false, true)) return;
        try {
            if (!java.nio.file.Files.isDirectory(outputDir) || !hasResults(outputDir)) return;
            org.kendar.llm.eval.report.ReportBuilder.build(outputDir, config.updateBaseline());
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
            EvalLog.info("report: " + outputDir.resolve("report.md") + " ; summary: " + summary.resolve("merged.md")
                    + " ; history: " + history);
        } catch (Exception e) {
            EvalLog.info("summary not written: " + config.secrets().mask(String.valueOf(e)));
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

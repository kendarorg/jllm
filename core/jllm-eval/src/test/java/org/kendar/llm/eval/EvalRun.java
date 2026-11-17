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
    private final LocalServer.Outcome localOutcome;

    private EvalRun(EvalConfig config, RunMetadata metadata, Path outputDir, Path metadataFile,
                    LocalServer.Outcome localOutcome) {
        this.config = config;
        this.metadata = metadata;
        this.outputDir = outputDir;
        this.metadataFile = metadataFile;
        this.localOutcome = localOutcome;
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
        EvalConfig config = EvalConfig.load();
        LocalServer server = null;
        LocalServer.Outcome outcome = null;
        if (config.targetKind() == EvalConfig.TargetKind.LOCAL) {
            server = LocalServer.testcontainers(config.local());
            outcome = server.ensureRunning();
            LocalServer s = server;
            Runtime.getRuntime().addShutdownHook(new Thread(s::finish));
        }
        ConnectionCheck.Result check = ConnectionCheck.run(config, EvalModels::client);
        RunMetadata meta = RunMetadata.of(config, check, start);
        Path dir = config.outputDir().resolve("run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(start));
        try {
            Path file = meta.write(dir);
            return new EvalRun(config, meta, dir, file, outcome);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot write run metadata: " + Secrets.mask(e.getMessage()), e);
        }
    }

    public EvalConfig config() { return config; }
    public RunMetadata metadata() { return metadata; }
    public Path outputDir() { return outputDir; }
    public Path metadataFile() { return metadataFile; }

    /** Null for non-local targets. */
    public LocalServer.Outcome localOutcome() { return localOutcome; }
}

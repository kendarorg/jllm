package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the docker CLI as a subprocess. Output is read with {@link TailCapture}: only the tail is kept in memory, so a
 * command printing without end ({@code yes}) cannot exhaust the heap.
 */
public class ProcessDockerCommandRunner implements DockerCommandRunner {

    /** Bytes kept per stream for control commands ({@code inspect}, {@code ps}...): enough for any JSON they print. */
    static final int CONTROL_CAPTURE_LIMIT = 1024 * 1024;

    @Override
    public Result run(List<String> argv, Duration timeout) throws IOException, InterruptedException {
        Result r = run(argv, timeout, CONTROL_CAPTURE_LIMIT, TailCapture.DEFAULT_LOG_LIMIT);
        if (r.log() != null) Files.deleteIfExists(r.log());
        return new Result(r.stdout(), r.stderr(), r.exitCode(), r.timedOut(), r.omittedLines(), null);
    }

    /**
     * Output may be cut to the last {@code captureLimit} bytes per stream; {@link Result#log()} is the spill log of
     * stdout (or of stderr when only that overflowed), null when nothing overflowed.
     */
    @Override
    public Result run(List<String> argv, Duration timeout, int captureLimit, long logLimit)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).start();
        p.getOutputStream().close();
        TailCapture out = new TailCapture(captureLimit, logLimit);
        TailCapture err = new TailCapture(captureLimit, logLimit);
        InputStream stdout = p.getInputStream();
        InputStream stderr = p.getErrorStream();
        CompletableFuture<TailCapture> outReader = out.start(stdout);
        CompletableFuture<TailCapture> errReader = err.start(stderr);
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            Duration wait = finished ? LocalDirSandboxExecutor.READER_GRACE : Duration.ofSeconds(5);
            out.await(outReader, stdout, wait);
            err.await(errReader, stderr, wait);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            out.detach();
            err.detach();
            throw e;
        }
        Path log = out.log();
        if (log == null) {
            log = err.log();
        } else if (err.log() != null) {
            Files.deleteIfExists(err.log());
        }
        int exit = finished && !p.isAlive() ? p.exitValue() : -1;
        return new Result(out.text(), err.text(), exit, !finished, out.omittedLines() + err.omittedLines(), log);
    }
}

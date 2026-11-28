package org.kendar.llm.harness.sandbox;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs a prepared process to completion or timeout, capturing the tail of its output ({@link TailCapture}). */
final class ProcessRun {

    /** Bytes of command output kept in memory; the complete output goes to a log file beyond this. */
    static final int CAPTURE_LIMIT = 50 * 1024;

    /**
     * How long the output is still read after the command exited. A background child that inherited the output pipe
     * keeps it open: past this the result is returned with the output read so far (the child keeps running).
     */
    static final Duration READER_GRACE = Duration.ofSeconds(2);

    private ProcessRun() {
    }

    static ExecutionResult run(ProcessBuilder pb, Duration timeout, long logLimit) {
        Process p = null;
        TailCapture capture = new TailCapture(CAPTURE_LIMIT, logLimit);
        try {
            p = pb.start();
            p.getOutputStream().close();
            InputStream stdout = p.getInputStream();
            CompletableFuture<TailCapture> out = capture.start(stdout);
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            capture.await(out, stdout, finished ? READER_GRACE : Duration.ofSeconds(5));
            return new ExecutionResult(capture.text(), "", finished ? p.exitValue() : -1, !finished,
                    capture.omittedLines(), capture.log());
        } catch (InterruptedException e) {
            if (p != null) { // cancelled: do not leave the command running
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
            capture.detach();
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Interrupted", -1, false);
        } catch (Exception e) { // boundary: sandbox boundary: detach, report the failure
            capture.detach();
            return new ExecutionResult("", "Execution failed: " + e.getMessage(), -1, false);
        }
    }
}

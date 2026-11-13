package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Seam over process spawning so the docker backend is unit-testable with a fake. */
public interface DockerCommandRunner {

    /**
     * @param omittedLines lines dropped from stdout and stderr together because the output exceeded the capture limit
     * @param log          the spill log of the full output; null when nothing overflowed
     */
    record Result(String stdout, String stderr, int exitCode, boolean timedOut, long omittedLines, Path log) {

        /** A result kept whole: nothing omitted, no log. */
        public Result(String stdout, String stderr, int exitCode, boolean timedOut) {
            this(stdout, stderr, exitCode, timedOut, 0, null);
        }
    }

    /**
     * Runs argv, killing the local process after {@code timeout}.
     *
     * @throws IOException          if the binary cannot be started
     * @throws InterruptedException if the calling thread is interrupted (the process is destroyed first)
     */
    Result run(List<String> argv, Duration timeout) throws IOException, InterruptedException;

    /**
     * As {@link #run(List, Duration)}, keeping at most {@code captureLimit} bytes per stream in memory and spilling
     * the rest to a log of at most {@code logLimit} bytes (0 = unlimited). The default keeps everything.
     */
    default Result run(List<String> argv, Duration timeout, int captureLimit, long logLimit)
            throws IOException, InterruptedException {
        return run(argv, timeout);
    }
}

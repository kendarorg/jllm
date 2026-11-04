package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Seam over process spawning so the docker backend is unit-testable with a fake. */
public interface DockerCommandRunner {

    record Result(String stdout, String stderr, int exitCode, boolean timedOut) {
    }

    /**
     * Runs argv, killing the local process after {@code timeout}.
     *
     * @throws IOException          if the binary cannot be started
     * @throws InterruptedException if the calling thread is interrupted (the process is destroyed first)
     */
    Result run(List<String> argv, Duration timeout) throws IOException, InterruptedException;
}

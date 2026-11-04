package org.kendar.llm.harness.sandbox;

import java.nio.file.Path;

/**
 * @param omittedLines lines dropped from the front of {@code stdout} because the output exceeded the capture limit
 * @param logFile      file holding the complete output when it was truncated; null otherwise
 */
public record ExecutionResult(String stdout, String stderr, int exitCode, boolean timedOut,
                              long omittedLines, Path logFile) {

    public ExecutionResult(String stdout, String stderr, int exitCode, boolean timedOut) {
        this(stdout, stderr, exitCode, timedOut, 0, null);
    }
}

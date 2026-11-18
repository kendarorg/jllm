package org.kendar.llm.harness.llm;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Caps the total time one model call may spend sleeping between retries, so a rate-limited provider fails the call
 * quickly instead of stalling a run for minutes.
 */
final class RetryBudget {

    static final long MAX_WAIT_MS = 10_000L;

    private long waitedMs;

    /**
     * @param attempt      the retry about to be made (1-based)
     * @param backoffBaseMs first delay; doubles on every further attempt
     * @param retryAfterMs the server's retry-after, used instead of the exponential delay when present
     * @return the delay to wait, or empty when waiting it would exceed {@link #MAX_WAIT_MS} in total
     */
    OptionalLong reserve(int attempt, long backoffBaseMs, Optional<Long> retryAfterMs) {
        long delay = retryAfterMs.orElseGet(() -> backoffBaseMs << (attempt - 1));
        if (waitedMs + delay > MAX_WAIT_MS) {
            return OptionalLong.empty();
        }
        waitedMs += delay;
        return OptionalLong.of(delay);
    }
}

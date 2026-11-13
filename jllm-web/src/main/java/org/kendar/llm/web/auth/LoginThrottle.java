package org.kendar.llm.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed password checks per key (login id, remote address): after {@link #MAX_FAILURES} the key is locked for
 * {@link #LOCK}. A success clears the key. In memory.
 */
public final class LoginThrottle {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK = Duration.ofSeconds(30);

    private record State(int failures, Instant lockedUntil) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Seconds the key is still locked; 0 when it is not. */
    public long lockedFor(String key) {
        State s = states.get(key);
        if (s == null || s.lockedUntil() == null) return 0;
        long left = Duration.between(clock.instant(), s.lockedUntil()).toSeconds();
        if (left <= 0 && !clock.instant().isBefore(s.lockedUntil())) {
            states.remove(key);
            return 0;
        }
        return Math.max(1, left);
    }

    public void failure(String key) {
        states.compute(key, (k, s) -> {
            int n = (s == null ? 0 : s.failures()) + 1;
            return n >= MAX_FAILURES ? new State(0, clock.instant().plus(LOCK)) : new State(n, null);
        });
    }

    public void success(String key) {
        states.remove(key);
    }
}

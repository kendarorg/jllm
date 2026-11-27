package org.kendar.llm.web.commons.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attempts per key, with a lock. Keys starting with {@code id:} (a login id and the address it came from, see
 * {@link #userKey}) have the user policy: {@value #USER_MAX_FAILURES} failed sign-ins lock that login for that address
 * for {@link #USER_LOCK}, so knowing a login does not let a stranger lock its owner out (an administrator can unlock
 * a login everywhere with {@link #resetUser}). Every other key (a remote address) has the address policy: {@value #ADDRESS_MAX_ATTEMPTS} attempts
 * in {@link #ADDRESS_LOCK} lock the address for that long.
 *
 * <p>{@link #begin} checks and counts in one atomic step, before the password is checked, so parallel guesses cannot
 * slip past the limit between a check and a later count; {@link #failure} locks the key when its attempts are used
 * up, {@link #success} forgets a login. The count of an address is never refunded by a success, so valid sign-ins of
 * an attacker's own account do not buy extra guesses. The attempts of a key expire when its window has passed.
 *
 * <p>In memory, and bounded: at most {@link #MAX_ENTRIES} keys; when full, the expired ones go first, then the oldest
 * unlocked ones. A lock is never evicted to make room: when every key is locked, an attempt for a new key is refused
 * (for as long as the earliest lock lasts) rather than letting a flood of keys wash a lock out. A restart clears every
 * lock.
 */
public final class LoginThrottle {

    public static final int USER_MAX_FAILURES = 3;
    public static final Duration USER_LOCK = Duration.ofMinutes(30);
    public static final int ADDRESS_MAX_ATTEMPTS = 100;
    public static final Duration ADDRESS_LOCK = Duration.ofMinutes(10);
    public static final int MAX_ENTRIES = 10_000;
    /** Prefix of the key of a login id. */
    public static final String USER_PREFIX = "id:";
    private static final char SEP = '\u0000';

    private record State(int attempts, Instant windowEnds, Instant lockedUntil) {
    }

    private final Map<String, State> states = new LinkedHashMap<>();
    private final Clock clock;
    private final int maxEntries;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    public LoginThrottle(Clock clock) {
        this(clock, MAX_ENTRIES);
    }

    /** @param maxEntries the most keys kept (at least 1) */
    public LoginThrottle(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = Math.max(1, maxEntries);
    }

    private static int max(String key) {
        return key.startsWith(USER_PREFIX) ? USER_MAX_FAILURES : ADDRESS_MAX_ATTEMPTS;
    }

    private static Duration window(String key) {
        return key.startsWith(USER_PREFIX) ? USER_LOCK : ADDRESS_LOCK;
    }

    /**
     * Admits an attempt and counts it, atomically.
     *
     * @return the seconds the key is locked (the attempt is refused and not counted); 0 when it was admitted
     */
    public synchronized long begin(String key) {
        Instant now = clock.instant();
        State s = states.get(key);
        if (s != null && s.lockedUntil() != null) {
            if (now.isBefore(s.lockedUntil())) return seconds(now, s.lockedUntil());
            states.remove(key);
            s = null;
        }
        if (s != null && !now.isBefore(s.windowEnds())) s = null; // the window of the earlier attempts is over
        if (s != null && s.attempts() >= max(key)) {
            // exhausted and not locked yet: attempts in flight use them up; refuse until one of them reports
            return 1;
        }
        State next = s == null ? new State(1, now.plus(window(key)), null) : new State(s.attempts() + 1, s.windowEnds(), null);
        return put(key, next, now);
    }

    /** The attempt of {@link #begin} was wrong: when the attempts of the key are used up, it is locked. */
    public synchronized void failure(String key) {
        State s = states.get(key);
        if (s == null || s.attempts() < max(key)) return;
        states.put(key, new State(s.attempts(), s.windowEnds(), clock.instant().plus(window(key))));
    }

    /** The attempt was right: a login id starts again; an address keeps its count. */
    public synchronized void success(String key) {
        if (key.startsWith(USER_PREFIX)) states.remove(key);
    }

    /** Seconds the key is still locked; 0 when it is not. Counts nothing. */
    public synchronized long lockedFor(String key) {
        State s = states.get(key);
        if (s == null || s.lockedUntil() == null) return 0;
        Instant now = clock.instant();
        if (!now.isBefore(s.lockedUntil())) {
            states.remove(key);
            return 0;
        }
        return seconds(now, s.lockedUntil());
    }

    /**
     * The key of a login id seen from an address: the lock is per login <em>and</em> address, so a stranger who knows
     * a login (administrator, say) can only lock it for their own address, never for its owner. A null address is
     * the signed-in caller (password change): only the account holder can reach it.
     */
    public static String userKey(String id, String address) {
        String safe = id == null ? "" : id.replace(SEP, ' ');
        if (safe.length() > 128) safe = safe.substring(0, 128);
        return USER_PREFIX + safe + SEP + (address == null ? "-" : address);
    }

    /** Longest remaining lock of a login over every address; 0 when none. Counts nothing. */
    public synchronized long lockedForUser(String id) {
        String prefix = userKey(id, "");
        long max = 0;
        for (String k : List.copyOf(states.keySet())) {
            if (k.startsWith(prefix)) max = Math.max(max, lockedFor(k));
        }
        return max;
    }

    /** Forgets every key of a login, whatever the address (the administrator's unlock). @return whether any existed */
    public synchronized boolean resetUser(String id) {
        String prefix = userKey(id, "");
        return states.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Forgets a key, locked or not. @return whether it had a state */
    public synchronized boolean reset(String key) {
        return states.remove(key) != null;
    }

    /** Number of keys kept. */
    public synchronized int size() {
        return states.size();
    }

    private static long seconds(Instant now, Instant until) {
        return Math.max(1, Duration.between(now, until).toSeconds());
    }

    /** @return 0 when stored; else the seconds until a lock ends and a slot frees (the table is full of locks) */
    private long put(String key, State s, Instant now) {
        boolean known = states.containsKey(key);
        states.remove(key); // re-inserted last: the oldest keys are first
        if (!known && states.size() >= maxEntries) {
            for (Iterator<State> it = states.values().iterator(); it.hasNext(); ) {
                State e = it.next();
                Instant end = e.lockedUntil() != null ? e.lockedUntil() : e.windowEnds();
                if (!now.isBefore(end)) it.remove();
            }
            for (Iterator<State> it = states.values().iterator(); it.hasNext() && states.size() >= maxEntries; ) {
                if (it.next().lockedUntil() == null) it.remove(); // never a lock
            }
            if (states.size() >= maxEntries) {
                Instant first = states.values().stream().map(State::lockedUntil).min(Instant::compareTo).orElse(now);
                return seconds(now, first);
            }
        }
        states.put(key, s);
        return 0;
    }
}

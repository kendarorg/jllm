package org.kendar.llm.web.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in sessions, in memory: a random token (the cookie value) names a user id until it expires (sliding) or is
 * revoked. A restart signs everybody out.
 */
public final class SessionStore {

    public static final Duration TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Session(String userId, Instant expiresAt) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public SessionStore() {
        this(Clock.systemUTC());
    }

    public SessionStore(Clock clock) {
        this.clock = clock;
    }

    public String create(String userId) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        sessions.put(token, new Session(userId, clock.instant().plus(TTL)));
        return token;
    }

    /** The user id of a live session; extends it. */
    public Optional<String> find(String token) {
        if (token == null) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        Instant now = clock.instant();
        if (now.isAfter(s.expiresAt())) {
            sessions.remove(token);
            return Optional.empty();
        }
        sessions.put(token, new Session(s.userId(), now.plus(TTL)));
        return Optional.of(s.userId());
    }

    public void revoke(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Signs a user out everywhere, except the session {@code keep} (null = none kept). */
    public void revokeUser(String userId, String keep) {
        sessions.entrySet().removeIf(e -> e.getValue().userId().equals(userId) && !e.getKey().equals(keep));
    }
}

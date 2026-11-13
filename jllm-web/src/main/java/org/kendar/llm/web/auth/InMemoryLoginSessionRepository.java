package org.kendar.llm.web.auth;

import org.kendar.llm.web.commons.users.LoginSessionRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The {@link LoginSessionRepository} in memory: a restart signs everybody out. */
public final class InMemoryLoginSessionRepository implements LoginSessionRepository {

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Session(String userId, Instant expiresAt) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryLoginSessionRepository() {
        this(Clock.systemUTC());
    }

    public InMemoryLoginSessionRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String create(String userId) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        sessions.put(token, new Session(userId, clock.instant().plus(TTL)));
        return token;
    }

    @Override
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

    @Override
    public void revoke(String token) {
        if (token != null) sessions.remove(token);
    }

    @Override
    public void revokeUser(String userId, String keep) {
        sessions.entrySet().removeIf(e -> e.getValue().userId().equals(userId) && !e.getKey().equals(keep));
    }
}

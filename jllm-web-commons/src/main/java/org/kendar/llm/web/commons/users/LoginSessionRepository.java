package org.kendar.llm.web.commons.users;

import java.time.Duration;
import java.util.Optional;

/**
 * Sign-in sessions: a random token (the cookie value) names a user id until it expires (sliding, {@link #TTL}) or is
 * revoked. The reference application keeps them in memory, so a restart signs everybody out.
 */
public interface LoginSessionRepository {

    Duration TTL = Duration.ofDays(7);

    /** A new session for the user. @return its token, unguessable (at least 256 random bits) */
    String create(String userId);

    /** The user id of a live session; extends it by {@link #TTL}. Empty when unknown, expired or null. */
    Optional<String> find(String token);

    void revoke(String token);

    /** Signs a user out everywhere, except the session {@code keep} (null = none kept). */
    void revokeUser(String userId, String keep);
}

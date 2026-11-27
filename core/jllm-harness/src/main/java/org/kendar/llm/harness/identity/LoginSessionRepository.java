package org.kendar.llm.harness.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sign-in sessions: a random token (the cookie value) names a user id until it expires (sliding, {@link #TTL}), passes
 * its hard limit ({@code notAfter}, when it has one) or is revoked. The reference application keeps them in memory, so a restart signs everybody out.
 */
public interface LoginSessionRepository {

    Duration TTL = Duration.ofDays(7);

    /**
     * A new session for the user.
     *
     * @param notAfter when the session ends whatever its use (the user must sign in again); null = no such limit
     * @return its token, unguessable (at least 256 random bits)
     */
    String create(String userId, Instant notAfter);

    /**
     * The user id of a live session; extends it by {@link #TTL}, never past its {@code notAfter}. Empty when unknown,
     * expired, past its {@code notAfter} or null.
     */
    Optional<String> find(String token);

    void revoke(String token);

    /** Signs a user out everywhere, except the session {@code keep} (null = none kept). */
    void revokeUser(String userId, String keep);
}

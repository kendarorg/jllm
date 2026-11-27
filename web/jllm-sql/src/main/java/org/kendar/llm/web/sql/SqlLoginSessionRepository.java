package org.kendar.llm.web.sql;

import org.kendar.llm.harness.identity.LoginSessionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * {@link LoginSessionRepository} on {@code login_sessions}: only a SHA-256 of the token is stored, so a database dump
 * signs nobody in. Sessions survive a restart and are shared between instances.
 */
public final class SqlLoginSessionRepository implements LoginSessionRepository {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Database db;
    private final Clock clock;

    public SqlLoginSessionRepository(Database db) {
        this(db, Clock.systemUTC());
    }

    public SqlLoginSessionRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public String create(String userId, Instant notAfter) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        long now = clock.millis();
        db.run(tx -> {
            tx.update("DELETE FROM login_sessions WHERE expires_at < ?", now);
            tx.update("INSERT INTO login_sessions (token_hash, user_id, expires_at, not_after) VALUES (?, ?, ?, ?)",
                    hash(token), userId, now + TTL.toMillis(), notAfter == null ? null : notAfter.toEpochMilli());
        });
        return token;
    }

    @Override
    public Optional<String> find(String token) {
        if (token == null) return Optional.empty();
        String h = hash(token);
        long now = clock.millis();
        return db.tx(tx -> {
            List<Object[]> rows = tx.query("SELECT user_id, expires_at, not_after FROM login_sessions WHERE token_hash = ?",
                    rs -> {
                        long notAfter = rs.getLong(3);
                        Long hardEnd = rs.wasNull() ? null : notAfter;
                        return new Object[]{rs.getString(1), rs.getLong(2), hardEnd};
                    }, h);
            if (rows.isEmpty()) return Optional.<String>empty();
            Long notAfter = (Long) rows.get(0)[2];
            if (now > (Long) rows.get(0)[1] || (notAfter != null && now > notAfter)) {
                tx.update("DELETE FROM login_sessions WHERE token_hash = ?", h);
                return Optional.<String>empty();
            }
            tx.update("UPDATE login_sessions SET expires_at = ? WHERE token_hash = ?", now + TTL.toMillis(), h);
            return Optional.of((String) rows.get(0)[0]);
        });
    }

    @Override
    public void revoke(String token) {
        if (token == null) return;
        db.run(tx -> tx.update("DELETE FROM login_sessions WHERE token_hash = ?", hash(token)));
    }

    @Override
    public void revokeUser(String userId, String keep) {
        db.run(tx -> {
            if (keep == null) tx.update("DELETE FROM login_sessions WHERE user_id = ?", userId);
            else tx.update("DELETE FROM login_sessions WHERE user_id = ? AND token_hash <> ?", userId, hash(keep));
        });
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

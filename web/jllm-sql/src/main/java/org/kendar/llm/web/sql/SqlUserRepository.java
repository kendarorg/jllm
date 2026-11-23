package org.kendar.llm.web.sql;

import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/** {@link UserRepository} on {@code users} and its child tables. Same rules as the file one. */
public final class SqlUserRepository implements UserRepository {

    private static final String COLS = "id, name, role, password_hash, external_id, email";

    private final Database db;

    public SqlUserRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<User> find(String id) {
        if (!UserRepository.validId(id)) return Optional.empty();
        return db.tx(tx -> load(tx, id));
    }

    @Override
    public List<User> all() {
        return db.tx(tx -> {
            List<String> ids = tx.query("SELECT id FROM users ORDER BY id", rs -> rs.getString(1));
            List<User> out = new ArrayList<>();
            for (String id : ids) load(tx, id).ifPresent(out::add);
            return out;
        });
    }

    @Override
    public boolean exists(String id) {
        return UserRepository.validId(id) && db.tx(tx -> tx.exists("SELECT 1 FROM users WHERE id = ?", id));
    }

    @Override
    public User create(String id, String name, String role, String passwordHash, String email) {
        return insert(id, name, role, passwordHash, email, null);
    }

    @Override
    public User createExternal(String id, String name, String role, String email, String externalId) {
        if (externalId == null || externalId.isBlank()) throw new IllegalArgumentException("externalId is required");
        return insert(id, name, role, null, email, externalId);
    }

    private User insert(String id, String name, String role, String passwordHash, String email, String externalId) {
        if (!UserRepository.validId(id)) throw new IllegalArgumentException("Invalid user id: " + id);
        return db.tx(tx -> {
            try {
                tx.update("INSERT INTO users (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?)", id,
                        name == null || name.isBlank() ? id : name, role, passwordHash,
                        externalId, email == null || email.isBlank() ? null : email.strip());
            } catch (RuntimeException e) {
                if (Tx.isDuplicate(e)) throw new IllegalStateException("User " + id + " exists");
                throw e;
            }
            return load(tx, id).orElseThrow();
        });
    }

    @Override
    public Optional<User> findByExternal(String issuer, String sub) {
        if (issuer == null || sub == null) return Optional.empty();
        String key = UserRepository.externalKey(issuer, sub);
        return db.tx(tx -> tx.query("SELECT id FROM users WHERE external_id = ? ORDER BY id", rs -> rs.getString(1), key)
                .stream().findFirst().flatMap(id -> load(tx, id)));
    }

    @Override
    public List<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return List.of();
        String wanted = email.strip().toLowerCase(java.util.Locale.ROOT);
        return db.tx(tx -> {
            List<User> out = new ArrayList<>();
            for (String id : tx.query("SELECT id FROM users WHERE lower(email) = ? ORDER BY id",
                    rs -> rs.getString(1), wanted)) {
                load(tx, id).ifPresent(out::add);
            }
            return out;
        });
    }

    @Override
    public User setExternalId(String id, String externalId) {
        return set(id, "external_id", externalId == null || externalId.isBlank() ? null : externalId);
    }

    @Override
    public User setEmail(String id, String email) {
        return set(id, "email", email == null || email.isBlank() ? null : email.strip());
    }

    @Override
    public User setName(String id, String name) {
        return set(id, "name", name == null || name.isBlank() ? id : name.strip());
    }

    @Override
    public User setRole(String id, String role) {
        return set(id, "role", role);
    }

    @Override
    public User setPassword(String id, String passwordHash) {
        return set(id, "password_hash", passwordHash);
    }

    private User set(String id, String column, String value) {
        return db.tx(tx -> {
            if (!UserRepository.validId(id) || tx.update("UPDATE users SET " + column + " = ? WHERE id = ?", value, id) == 0) {
                throw new NoSuchElementException("Unknown user " + id);
            }
            return load(tx, id).orElseThrow();
        });
    }

    @Override
    public User setCredentials(String id, Map<String, Map<String, String>> credentials) {
        UserRepository.checkCredentials(credentials);
        return db.tx(tx -> {
            requireUser(tx, id);
            tx.update("DELETE FROM user_credentials WHERE user_id = ?", id);
            if (credentials != null) {
                credentials.forEach((source, headers) -> {
                    if (headers == null || headers.isEmpty()) {
                        tx.update("INSERT INTO user_credentials (user_id, source, header, value) VALUES (?, ?, '', '')",
                                id, source); // a source without headers is kept, as in the file
                    } else {
                        headers.forEach((h, v) -> tx.update(
                                "INSERT INTO user_credentials (user_id, source, header, value) VALUES (?, ?, ?, ?)",
                                id, source, h, v));
                    }
                });
            }
            return load(tx, id).orElseThrow();
        });
    }

    @Override
    public User setDisabledTools(String id, List<String> patterns) {
        return setList(id, "user_disabled_tools", "pattern", patterns);
    }

    @Override
    public User setDisabledSkills(String id, List<String> names) {
        return setList(id, "user_disabled_skills", "name", names);
    }

    private User setList(String id, String table, String column, List<String> values) {
        return db.tx(tx -> {
            requireUser(tx, id);
            tx.update("DELETE FROM " + table + " WHERE user_id = ?", id);
            if (values != null) {
                int ord = 0;
                for (String v : values) {
                    tx.update("INSERT INTO " + table + " (user_id, ord, " + column + ") VALUES (?, ?, ?)", id, ord++, v);
                }
            }
            return load(tx, id).orElseThrow();
        });
    }

    @Override
    public boolean delete(String id) {
        if (!UserRepository.validId(id)) return false;
        return db.tx(tx -> {
            for (String t : List.of("user_credentials", "user_disabled_tools", "user_disabled_skills")) {
                tx.update("DELETE FROM " + t + " WHERE user_id = ?", id);
            }
            return tx.update("DELETE FROM users WHERE id = ?", id) > 0;
        });
    }

    private static void requireUser(Tx tx, String id) {
        if (!UserRepository.validId(id) || !tx.exists("SELECT 1 FROM users WHERE id = ?", id)) {
            throw new NoSuchElementException("Unknown user " + id);
        }
    }

    private static Optional<User> load(Tx tx, String id) {
        return tx.one("SELECT " + COLS + " FROM users WHERE id = ?", rs -> new String[]{
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)
        }, id).map(r -> {
            Map<String, Map<String, String>> creds = new LinkedHashMap<>();
            tx.query("SELECT source, header, value FROM user_credentials WHERE user_id = ? ORDER BY source, header",
                    rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, id).forEach(c -> {
                Map<String, String> headers = creds.computeIfAbsent(c[0], k -> new LinkedHashMap<>());
                if (!c[1].isEmpty()) headers.put(c[1], c[2]);
            });
            List<String> tools = tx.query("SELECT pattern FROM user_disabled_tools WHERE user_id = ? ORDER BY ord",
                    rs -> rs.getString(1), id);
            List<String> skills = tx.query("SELECT name FROM user_disabled_skills WHERE user_id = ? ORDER BY ord",
                    rs -> rs.getString(1), id);
            return new User(r[0], r[1], r[2] == null ? "none" : r[2], creds, r[3], tools, skills, r[4], r[5]);
        });
    }
}

package org.kendar.llm.web.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.auth.OAuthToken;
import org.kendar.llm.harness.auth.TokenCipher;
import org.kendar.llm.harness.auth.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link TokenStore} on {@code oauth_tokens(principal_id, name, payload, version)}. With a {@link TokenCipher} the
 * payload is AES-GCM sealed (a plain row is read and rewritten sealed on the next write; a token written with
 * {@code JLLM_TOKEN_KEY_OLD} is resealed when read); without one it is plain JSON, as the file store does. The
 * principal null is stored as the empty string. {@link #compareAndPut} is an {@code UPDATE ... WHERE version = ?},
 * so it also excludes other processes.
 */
public final class SqlTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(SqlTokenStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Row(String payload, long version) {
    }

    private final Database db;
    private final TokenCipher cipher;

    /** @param cipher null = store the tokens in clear */
    public SqlTokenStore(Database db, TokenCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    @Override
    public Optional<OAuthToken> get(String principalId, String name) {
        String p = key(principalId);
        Optional<Row> row = db.tx(tx -> row(tx, p, name));
        if (row.isEmpty()) return Optional.empty();
        Optional<OAuthToken> token = decode(row.get().payload(), p, name);
        if (token.isPresent() && cipher != null && needsReseal(row.get().payload())) {
            try {
                db.run(tx -> tx.update("UPDATE oauth_tokens SET payload = ?, version = version + 1"
                                + " WHERE principal_id = ? AND name = ? AND version = ?",
                        encode(token.get(), p, name), p, name, row.get().version()));
            } catch (RuntimeException e) { // boundary: resealing is best effort
                log.warn("Cannot reseal token {} of {}: {}", name, p, e.toString());
            }
        }
        return token;
    }

    @Override
    public void put(String principalId, String name, OAuthToken token) {
        String p = key(principalId);
        String payload = encode(token, p, name);
        db.run(tx -> {
            if (tx.update("UPDATE oauth_tokens SET payload = ?, version = version + 1"
                    + " WHERE principal_id = ? AND name = ?", payload, p, name) == 0) {
                tx.update("INSERT INTO oauth_tokens (principal_id, name, payload, version) VALUES (?, ?, ?, 1)",
                        p, name, payload);
            }
        });
    }

    @Override
    public boolean remove(String principalId, String name) {
        return db.tx(tx -> tx.update("DELETE FROM oauth_tokens WHERE principal_id = ? AND name = ?",
                key(principalId), name) > 0);
    }

    @Override
    public List<String> names(String principalId) {
        return db.tx(tx -> tx.query("SELECT name FROM oauth_tokens WHERE principal_id = ? ORDER BY name",
                rs -> rs.getString(1), key(principalId)));
    }

    @Override
    public void removeAll(String principalId) {
        db.run(tx -> tx.update("DELETE FROM oauth_tokens WHERE principal_id = ?", key(principalId)));
    }

    @Override
    public boolean compareAndPut(String principalId, String name, OAuthToken expected, OAuthToken next) {
        String p = key(principalId);
        return db.tx(tx -> {
            Optional<Row> row = row(tx, p, name);
            if (row.isEmpty()) {
                if (expected != null) return false;
                if (next == null) return true;
                try {
                    tx.update("INSERT INTO oauth_tokens (principal_id, name, payload, version) VALUES (?, ?, ?, 1)",
                            p, name, encode(next, p, name));
                    return true;
                } catch (RuntimeException e) {
                    if (Tx.isDuplicate(e)) return false; // another writer inserted first
                    throw e;
                }
            }
            if (expected == null) return false;
            OAuthToken cur = decode(row.get().payload(), p, name).orElse(null);
            if (!Objects.equals(cur, expected)) return false;
            if (next == null) {
                return tx.update("DELETE FROM oauth_tokens WHERE principal_id = ? AND name = ? AND version = ?",
                        p, name, row.get().version()) > 0;
            }
            return tx.update("UPDATE oauth_tokens SET payload = ?, version = version + 1"
                            + " WHERE principal_id = ? AND name = ? AND version = ?",
                    encode(next, p, name), p, name, row.get().version()) > 0;
        });
    }

    @Override
    public boolean crossProcess() {
        return true;
    }

    @Override
    public String describe() {
        return "sql " + db.dialect().dir() + (cipher == null ? " (not encrypted)" : " (encrypted)");
    }

    private static String key(String principalId) {
        return principalId == null ? "" : principalId;
    }

    private static Optional<Row> row(Tx tx, String principal, String name) {
        return tx.one("SELECT payload, version FROM oauth_tokens WHERE principal_id = ? AND name = ?",
                rs -> new Row(rs.getString(1), rs.getLong(2)), principal, name);
    }

    /** The sealed form is bound to the row: user id and token name are associated data. */
    private String encode(OAuthToken t, String principal, String name) {
        try {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("access", t.access());
            if (t.refresh() != null) n.put("refresh", t.refresh());
            n.put("expiresAt", t.expiresAt());
            ObjectNode extra = n.putObject("extra");
            t.extra().forEach(extra::put);
            String json = MAPPER.writeValueAsString(n);
            if (cipher == null) return MAPPER.writeValueAsString(MAPPER.createObjectNode().set("token", n));
            TokenCipher.Sealed s = cipher.encrypt(json, TokenCipher.context(principal, name));
            ObjectNode out = MAPPER.createObjectNode();
            out.putObject("sealed").put("kid", s.kid()).put("nonce", s.nonce()).put("data", s.data());
            return MAPPER.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean needsReseal(String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (root.has("token")) return true; // plain row, now a key is set
            JsonNode s = root.path("sealed");
            return cipher.isOldKey(new TokenCipher.Sealed(s.path("kid").asText(), s.path("nonce").asText(),
                    s.path("data").asText()));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private Optional<OAuthToken> decode(String payload, String principal, String name) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode n;
            if (root.has("token")) {
                n = root.get("token");
            } else if (root.has("sealed")) {
                if (cipher == null) {
                    log.warn("Token {} of '{}' is encrypted, but no {} is set", name, principal, TokenCipher.KEY_ENV);
                    return Optional.empty();
                }
                JsonNode s = root.get("sealed");
                n = MAPPER.readTree(cipher.decrypt(new TokenCipher.Sealed(s.path("kid").asText(),
                        s.path("nonce").asText(), s.path("data").asText()), TokenCipher.context(principal, name)));
            } else {
                throw new IllegalArgumentException("unknown payload");
            }
            Map<String, String> extra = new HashMap<>();
            n.path("extra").fields().forEachRemaining(e -> extra.put(e.getKey(), e.getValue().asText()));
            return Optional.of(new OAuthToken(n.path("access").asText(),
                    n.hasNonNull("refresh") ? n.get("refresh").asText() : null, n.path("expiresAt").asLong(0), extra));
        } catch (TokenCipher.DecryptException | JsonProcessingException | RuntimeException e) {
            log.warn("Token {} of '{}' is unreadable: {}", name, principal, e.getMessage()); // never the token
            return Optional.empty();
        }
    }
}

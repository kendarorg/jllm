package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Encrypted tokens are bound to their user and source: the user id and the source are AES-GCM associated data. */
class TokenBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private static TokenCipher cipher() {
        byte[] key = new byte[32];
        key[0] = 7;
        return new TokenCipher(key, null);
    }

    @Test
    void aValueDecryptsOnlyInItsOwnContext() throws Exception {
        TokenCipher c = cipher();
        TokenCipher.Sealed s = c.encrypt("secret", TokenCipher.context("alice", "mcp:x"));

        assertEquals("secret", c.decrypt(s, TokenCipher.context("alice", "mcp:x")));
        assertThrows(TokenCipher.DecryptException.class, () -> c.decrypt(s, TokenCipher.context("bob", "mcp:x")));
        assertThrows(TokenCipher.DecryptException.class, () -> c.decrypt(s, TokenCipher.context("alice", "mcp:y")));
        assertThrows(TokenCipher.DecryptException.class, () -> c.decrypt(s, TokenCipher.context(null, "mcp:x")));
        assertNotEquals(TokenCipher.context("ab", "c"), TokenCipher.context("a", "bc"), "unambiguous join");
    }

    @Test
    void aTokenFileCopiedToAnotherUserIsRejected() throws Exception {
        FileTokenStore store = new FileTokenStore(dir, cipher());
        store.put("alice", "mcp:x", new OAuthToken("alice-token", "r", 1, Map.of()));
        Files.copy(store.fileOf("alice"), store.fileOf("bob"));

        assertTrue(store.get("bob", "mcp:x").isEmpty(), "bob must not get alice's token");
        assertEquals("alice-token", store.get("alice", "mcp:x").orElseThrow().access());
    }

    @Test
    void aTokenRenamedToAnotherSourceInsideTheFileIsRejected() throws Exception {
        FileTokenStore store = new FileTokenStore(dir, cipher());
        store.put("alice", "mcp:x", new OAuthToken("alice-token", "r", 1, Map.of()));
        Path f = store.fileOf("alice");
        ObjectNode root = (ObjectNode) MAPPER.readTree(f.toFile());
        ObjectNode entries = (ObjectNode) root.get("entries");
        JsonNode moved = entries.remove("mcp:x");
        entries.set("mcp:other", moved);
        MAPPER.writeValue(f.toFile(), root);

        assertTrue(store.get("alice", "mcp:other").isEmpty());
        assertTrue(store.get("alice", "mcp:x").isEmpty());
    }

    @Test
    void theSharedFileCannotBeMovedIntoAUserFile() throws Exception {
        FileTokenStore store = new FileTokenStore(dir, cipher());
        store.put(null, "api:shared", new OAuthToken("shared-token", "r", 1, Map.of()));
        Files.createDirectories(store.fileOf("alice").getParent());
        Files.copy(store.fileOf(null), store.fileOf("alice"));

        assertTrue(store.get("alice", "api:shared").isEmpty());
        assertEquals("shared-token", store.get(null, "api:shared").orElseThrow().access());
    }

    @Test
    void eachTokenIsSealedOnItsOwnAndRoundTrips() throws Exception {
        FileTokenStore store = new FileTokenStore(dir, cipher());
        store.put("alice", "mcp:x", new OAuthToken("one", "r1", 1, Map.of("k", "v")));
        store.put("alice", "mcp:y", new OAuthToken("two", "r2", 2, Map.of()));

        String text = Files.readString(store.fileOf("alice"));
        assertFalse(text.contains("\"access\"") || text.contains("\"refresh\""), "nothing in clear: " + text);
        assertEquals(2, MAPPER.readTree(text).path("entries").size());
        assertEquals("v", store.get("alice", "mcp:x").orElseThrow().extra().get("k"));
        assertEquals(java.util.List.of("mcp:x", "mcp:y"), store.names("alice"));
        assertTrue(store.remove("alice", "mcp:x"));
        assertEquals("two", store.get("alice", "mcp:y").orElseThrow().access());
    }
}

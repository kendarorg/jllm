package org.kendar.llm.harness.auth;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The behaviour every {@link TokenStore} must have, run against each implementation. */
class TokenStoreContractTest {

    abstract static class Contract {

        @TempDir
        Path tmp;

        abstract TokenStore store() throws Exception;

        /** Native stores of the OS have their own limits on principal ids; the others accept file-safe ids. */
        String principal() {
            return "alice";
        }

        private static OAuthToken tok(String access) {
            return new OAuthToken(access, "r-" + access, 123, Map.of("client_id", "c"));
        }

        @Test
        void getPutRemoveNames() throws Exception {
            TokenStore s = store();
            assertTrue(s.get(null, "api:x").isEmpty());
            s.put(null, "api:x", tok("a"));
            s.put(null, "mcp:y", tok("b"));
            assertEquals("a", s.get(null, "api:x").orElseThrow().access());
            assertEquals("r-a", s.get(null, "api:x").orElseThrow().refresh());
            assertEquals("c", s.get(null, "api:x").orElseThrow().extra().get("client_id"));
            assertEquals(List.of("api:x", "mcp:y"), s.names(null));
            assertTrue(s.remove(null, "api:x"));
            assertFalse(s.remove(null, "api:x"));
            assertEquals(List.of("mcp:y"), s.names(null));
            assertNotNull(s.describe());
        }

        @Test
        void principalsAreIsolatedAndRemoveAllClearsOne() throws Exception {
            TokenStore s = store();
            s.put(null, "api:x", tok("shared"));
            s.put(principal(), "api:x", tok("mine"));
            s.put("bob", "api:x", tok("bobs"));
            assertEquals("shared", s.get(null, "api:x").orElseThrow().access());
            assertEquals("mine", s.get(principal(), "api:x").orElseThrow().access());
            s.removeAll(principal());
            assertTrue(s.get(principal(), "api:x").isEmpty());
            assertTrue(s.names(principal()).isEmpty());
            assertEquals("bobs", s.get("bob", "api:x").orElseThrow().access());
            assertEquals("shared", s.get(null, "api:x").orElseThrow().access());
        }

        @Test
        void compareAndPutSucceedsOnlyOnTheExpectedToken() throws Exception {
            TokenStore s = store();
            assertTrue(s.compareAndPut(null, "n", null, tok("1")));
            assertFalse(s.compareAndPut(null, "n", null, tok("2")));
            assertTrue(s.compareAndPut(null, "n", tok("1"), tok("3")));
            assertFalse(s.compareAndPut(null, "n", tok("1"), tok("4")));
            assertEquals("3", s.get(null, "n").orElseThrow().access());
            assertTrue(s.compareAndPut(null, "n", tok("3"), null));
            assertTrue(s.get(null, "n").isEmpty());
        }

        @Test
        void lockedExcludesOtherThreads() throws Exception {
            TokenStore s = store();
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger max = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                threads.add(Thread.ofPlatform().start(() -> s.locked(null, "n", () -> {
                    max.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return null;
                })));
            }
            for (Thread t : threads) t.join();
            assertEquals(1, max.get());
        }

        @Test
        void unusualNamesAndLargeTokens() throws Exception {
            TokenStore s = store();
            String name = "api:wéird \"name\" / with:colons & spaces";
            String big = "x".repeat(10_000);
            s.put(null, name, new OAuthToken(big, big, 0, Map.of("k\"ey", "v\nal")));
            OAuthToken back = s.get(null, name).orElseThrow();
            assertEquals(big, back.access());
            assertEquals("v\nal", back.extra().get("k\"ey"));
            assertTrue(s.names(null).contains(name));
        }
    }

    @Nested
    class FilePlain extends Contract {
        @Override
        TokenStore store() {
            return new FileTokenStore(tmp);
        }
    }

    @Nested
    class FileEncrypted extends Contract {
        @Override
        TokenStore store() {
            return new FileTokenStore(tmp, new TokenCipher(Base64.getDecoder().decode(TokenCipher.newKey()), null));
        }
    }

    /** The simplest embedder store: a map, with the default locking. */
    @Nested
    class InMemory extends Contract {
        @Override
        TokenStore store() {
            return new MemoryTokenStore();
        }
    }

    /** The native store over a fake OS backend. */
    @Nested
    class NativeFake extends Contract {
        @Override
        TokenStore store() {
            return new NativeTokenStore(new MapBackend());
        }
    }

    /** The real Windows DPAPI backend (the blob file goes to the temp directory). */
    @Nested
    class NativeWindows extends Contract {
        @Override
        TokenStore store() {
            Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
            NativeTokenStore s = NativeTokenStore.detect(System.getProperty("os.name"), tmp, NativeTokenStore::exec,
                    why -> { });
            Assumptions.assumeTrue(s != null, "DPAPI not usable here");
            return s;
        }
    }

    static final class MemoryTokenStore implements TokenStore {
        private final Map<String, OAuthToken> map = new ConcurrentHashMap<>();

        private static String key(String p, String n) {
            return (p == null ? "-" : p) + "\u0000" + n;
        }

        @Override
        public Optional<OAuthToken> get(String principalId, String name) {
            return Optional.ofNullable(map.get(key(principalId, name)));
        }

        @Override
        public void put(String principalId, String name, OAuthToken token) {
            map.put(key(principalId, name), token);
        }

        @Override
        public boolean remove(String principalId, String name) {
            return map.remove(key(principalId, name)) != null;
        }

        @Override
        public List<String> names(String principalId) {
            String prefix = (principalId == null ? "-" : principalId) + "\u0000";
            return map.keySet().stream().filter(k -> k.startsWith(prefix)).map(k -> k.substring(prefix.length()))
                    .sorted().toList();
        }

        @Override
        public String describe() {
            return "memory";
        }
    }

    static final class MapBackend implements NativeTokenStore.Backend {
        final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public String describe() {
            return "fake OS store";
        }

        @Override
        public Optional<String> read(String entry) {
            return Optional.ofNullable(values.get(entry));
        }

        @Override
        public void write(String entry, String value) {
            values.put(entry, value);
        }

        @Override
        public void delete(String entry) throws IOException {
            values.remove(entry);
        }
    }
}

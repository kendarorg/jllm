package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FileTokenStore}, {@link TokenCipher}, {@link NativeTokenStore} and {@link TokenStores} specifics. */
class TokenStoreImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static TokenCipher cipher(String key, String old) {
        return TokenCipher.fromEnv(Map.of(TokenCipher.KEY_ENV, key, TokenCipher.OLD_KEY_ENV, old == null ? "" : old)::get);
    }

    @Test
    void readsTodaysAuthJsonUnchanged() throws IOException {
        Files.writeString(tmp.resolve("auth.json"), """
                {"mcp:docs": {"access": "a1", "refresh": "r1", "expiresAt": 77,
                  "extra": {"client_id": "c", "token_endpoint": "https://t"}}}
                """);
        FileTokenStore s = new FileTokenStore(tmp);
        OAuthToken t = s.get(null, "mcp:docs").orElseThrow();
        assertEquals("a1", t.access());
        assertEquals("r1", t.refresh());
        assertEquals(77, t.expiresAt());
        assertEquals("https://t", t.extra().get("token_endpoint"));
        assertEquals(tmp.resolve("users").resolve("bob.json").toAbsolutePath().normalize(), s.fileOf("bob"));
        assertThrows(IllegalArgumentException.class, () -> s.fileOf("../evil"));
    }

    @Test
    void encryptedFilesHoldNoPlainTokenAndSurviveARoundTrip() throws IOException {
        String key = TokenCipher.newKey();
        FileTokenStore s = new FileTokenStore(tmp, cipher(key, null));
        s.put("alice", "api:crm", new OAuthToken("secret-access", "secret-refresh", 1, null));
        String text = Files.readString(s.fileOf("alice"));
        assertFalse(text.contains("secret"), text);
        assertTrue(text.contains("jllmEncrypted"));
        assertEquals("secret-access", new FileTokenStore(tmp, cipher(key, null)).get("alice", "api:crm")
                .orElseThrow().access());
    }

    @Test
    void tamperedOrWrongKeyFileIsReadAsEmpty() throws IOException {
        FileTokenStore s = new FileTokenStore(tmp, cipher(TokenCipher.newKey(), null));
        s.put(null, "api:crm", new OAuthToken("a", null, 0, null));
        assertTrue(new FileTokenStore(tmp, cipher(TokenCipher.newKey(), null)).get(null, "api:crm").isEmpty());
        assertTrue(new FileTokenStore(tmp).get(null, "api:crm").isEmpty()); // no key at all

        Path f = s.fileOf(null);
        ObjectNode env = (ObjectNode) MAPPER.readTree(f.toFile());
        byte[] data = Base64.getDecoder().decode(env.get("data").asText());
        data[0] ^= 1;
        env.put("data", Base64.getEncoder().encodeToString(data));
        Files.writeString(f, env.toString());
        assertTrue(s.get(null, "api:crm").isEmpty());
        s.put(null, "api:crm", new OAuthToken("again", null, 0, null)); // the user logs in again
        assertEquals("again", s.get(null, "api:crm").orElseThrow().access());
    }

    @Test
    void keyRotationReadsWithTheOldKeyAndRewritesWithTheNewOne() throws IOException {
        String oldKey = TokenCipher.newKey();
        String newKey = TokenCipher.newKey();
        new FileTokenStore(tmp, cipher(oldKey, null)).put(null, "api:crm", new OAuthToken("a", null, 0, null));
        TokenCipher rotated = cipher(newKey, oldKey);
        FileTokenStore s = new FileTokenStore(tmp, rotated);
        assertEquals("a", s.get(null, "api:crm").orElseThrow().access());
        assertEquals(rotated.keyId(), MAPPER.readTree(s.fileOf(null).toFile()).get("kid").asText());
        assertEquals("a", new FileTokenStore(tmp, cipher(newKey, null)).get(null, "api:crm").orElseThrow().access());
    }

    @Test
    void cipherRejectsBadKeys() {
        assertNull(TokenCipher.fromEnv(k -> null));
        assertThrows(IllegalArgumentException.class, () -> TokenCipher.fromEnv(Map.of(TokenCipher.KEY_ENV, "short")::get));
        assertThrows(IllegalArgumentException.class, () -> TokenCipher.fromEnv(Map.of(TokenCipher.KEY_ENV, "!!!")::get));
    }

    // ---- native

    /** Records the commands; answers like the OS tool would, backed by a map. */
    private static final class FakeRunner implements NativeTokenStore.ProcessRunner {
        final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        final List<String> stdins = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> secrets = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean fail;
        volatile boolean hang;

        @Override
        public Result run(List<String> command, String stdin, Duration timeout) throws IOException {
            commands.add(command);
            stdins.add(stdin);
            if (hang) throw new IOException(command.get(0) + " did not answer in " + timeout.toSeconds() + " s");
            if (fail) return new Result(1, "", "no secret service on the bus");
            String tool = command.get(0);
            if (tool.equals("secret-tool")) {
                String entry = command.get(command.size() - 1);
                return switch (command.get(1)) {
                    case "store" -> {
                        secrets.put(entry, stdin);
                        yield new Result(0, "", "");
                    }
                    case "lookup" -> secrets.containsKey(entry) ? new Result(0, secrets.get(entry), "")
                            : new Result(1, "", "");
                    default -> {
                        secrets.remove(entry);
                        yield new Result(0, "", "");
                    }
                };
            }
            if (tool.equals("powershell")) { // identity "encryption": the blob is the base64 payload itself
                return new Result(0, stdin.strip(), "");
            }
            // macOS: parse the one interactive command
            String line = stdin.strip();
            String entry = line.replaceAll(".* -a \"([^\"]*)\".*", "$1");
            if (line.startsWith("add-generic-password")) {
                secrets.put(entry, line.replaceAll(".* -w \"([^\"]*)\"$", "$1"));
                return new Result(0, "", "");
            }
            if (line.startsWith("find-generic-password")) {
                return secrets.containsKey(entry) ? new Result(0, secrets.get(entry) + "\n", "")
                        : new Result(0, "", "security: The specified item could not be found in the keychain.");
            }
            secrets.remove(entry);
            return new Result(0, "", "");
        }

        /** Whether any command line carried the text. */
        boolean inArgs(String text) {
            return commands.stream().anyMatch(c -> c.stream().anyMatch(a -> a.contains(text)));
        }
    }

    @Test
    void nativeBackendIsChosenByOsAndSecretsNeverGoOnTheCommandLine() {
        for (String os : List.of("Mac OS X", "Linux", "Windows 11")) {
            FakeRunner runner = new FakeRunner();
            NativeTokenStore s = NativeTokenStore.detect(os, tmp.resolve(os.replace(' ', '_')), runner, w -> fail(w));
            assertNotNull(s, os);
            s.put(null, "api:crm", new OAuthToken("SECRET-ACCESS", "SECRET-REFRESH", 0, null));
            assertEquals("SECRET-ACCESS", new NativeTokenStore(s.backend()).get(null, "api:crm").orElseThrow().access(), os);
            assertFalse(runner.inArgs("SECRET"), os + ": " + runner.commands);
        }
        assertTrue(NativeTokenStore.backendFor("Mac OS X", tmp, new FakeRunner()).describe().contains("macOS"));
        assertTrue(NativeTokenStore.backendFor("Linux", tmp, new FakeRunner()).describe().contains("Secret Service"));
        assertTrue(NativeTokenStore.backendFor("Windows 10", tmp, new FakeRunner()).describe().contains("DPAPI"));
        assertNull(NativeTokenStore.backendFor("Plan9", tmp, new FakeRunner()));
    }

    @Test
    void failingProbeOrTimeoutFallsBackWithAReason() {
        FakeRunner failing = new FakeRunner();
        failing.fail = true;
        List<String> why = new ArrayList<>();
        assertNull(NativeTokenStore.detect("Linux", tmp, failing, why::add));
        assertTrue(why.get(0).contains("not usable"), why.toString());
        FakeRunner hanging = new FakeRunner();
        hanging.hang = true;
        why.clear();
        assertNull(NativeTokenStore.detect("Linux", tmp, hanging, why::add));
        assertTrue(why.get(0).contains("did not answer"), why.toString());
        assertNull(NativeTokenStore.detect("Plan9", tmp, new FakeRunner(), why::add));
    }

    @Test
    void firstNativeRunMovesAuthJsonOnce() throws IOException {
        Path auth = tmp.resolve("auth.json");
        FileTokenStore.ofFile(auth).put(null, "mcp:docs", new OAuthToken("a", "r", 0, null));
        NativeTokenStore s = new NativeTokenStore(new TokenStoreContractTest.MapBackend());
        assertEquals(1, s.migrateFrom(auth));
        assertEquals("a", s.get(null, "mcp:docs").orElseThrow().access());
        assertFalse(Files.exists(auth));
        assertTrue(Files.exists(tmp.resolve("auth.json.migrated")));
        assertEquals(0, s.migrateFrom(auth));
    }

    // ---- settings

    @Test
    void storeSettingsAreValidated() {
        Map<String, String> noKey = Map.of();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TokenStores.create(
                new HarnessSettings.TokenStoreSettings("native", null, null), true, tmp, noKey::get))
                .getMessage().contains("single user"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TokenStores.create(
                new HarnessSettings.TokenStoreSettings("file", null, true), false, tmp, noKey::get))
                .getMessage().contains("JLLM_TOKEN_KEY"));
        assertThrows(IllegalArgumentException.class, () -> TokenStores.create(
                new HarnessSettings.TokenStoreSettings("vault", null, null), false, tmp, noKey::get));
        // with users: encrypted when the key is there, plain without (an embedder decides whether that is enough)
        Map<String, String> key = Map.of(TokenCipher.KEY_ENV, TokenCipher.newKey());
        assertTrue(((FileTokenStore) TokenStores.create(null, true, tmp, key::get)).encrypted());
        assertFalse(((FileTokenStore) TokenStores.create(null, true, tmp, noKey::get)).encrypted());
        assertTrue(TokenStores.create(new HarnessSettings.TokenStoreSettings("file", null, null), false, tmp,
                noKey::get) instanceof FileTokenStore);
        // auto for a single user: chosen on first use only
        assertTrue(TokenStores.create(null, false, tmp, noKey::get) instanceof TokenStores.Lazy);
    }
}

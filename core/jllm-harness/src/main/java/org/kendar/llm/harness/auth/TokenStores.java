package org.kendar.llm.harness.auth;

import org.kendar.llm.harness.settings.HarnessSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Builds the {@link TokenStore} described by the {@code tokenStore} settings. */
public final class TokenStores {

    private static final Logger log = LoggerFactory.getLogger(TokenStores.class);

    private TokenStores() {
    }

    /**
     * @param settings   the {@code tokenStore} section; null = defaults
     * @param multiUser  the harness serves several users (each with their own tokens)
     * @param defaultDir where the file store writes when {@code dir} is not set; null = {@code ~/.jllm} for a single
     *                   user, {@code ~/.jllm/tokens} with users
     * @throws IllegalArgumentException for {@code native} with users, {@code encrypt} without {@code JLLM_TOKEN_KEY},
     *                                  an unknown type, or a native store that does not work on this machine
     */
    public static TokenStore create(HarnessSettings.TokenStoreSettings settings, boolean multiUser, Path defaultDir,
                                    Function<String, String> env) {
        String type = settings == null || settings.type() == null || settings.type().isBlank() ? "auto"
                : settings.type().strip().toLowerCase();
        if (!List.of("auto", "file", "native").contains(type)) {
            throw new IllegalArgumentException("tokenStore.type '" + type + "' is unknown (auto, file or native)");
        }
        if (type.equals("native") && multiUser) {
            throw new IllegalArgumentException("tokenStore.type native is for a single user: one OS account would hold"
                    + " every user's tokens; use file (encrypted)");
        }
        boolean explicit = settings != null && settings.encrypt() != null;
        TokenCipher cipher = TokenCipher.fromEnv(env);
        // with users the default is to encrypt; without a key the files stay plain, and an embedder with per-user
        // sources refuses to start (jllm-web does): a harness with no OAuth source needs no key
        boolean encrypt = explicit ? settings.encrypt() : multiUser && cipher != null;
        if (encrypt && cipher == null) {
            throw new IllegalArgumentException("tokenStore.encrypt needs " + TokenCipher.KEY_ENV
                    + " (32 bytes, base64)");
        }
        Path dir = settings != null && settings.dir() != null && !settings.dir().isBlank() ? Path.of(settings.dir())
                : defaultDir != null ? defaultDir
                : multiUser ? FileTokenStore.defaultDir().resolve("tokens") : FileTokenStore.defaultDir();
        Path abs = dir.toAbsolutePath().normalize();
        FileTokenStore file = !encrypt && abs.equals(FileTokenStore.defaultDir().toAbsolutePath().normalize())
                ? FileTokenStore.defaultStore() : new FileTokenStore(abs, encrypt ? cipher : null);
        return switch (type) {
            case "file" -> file;
            case "native" -> {
                StringBuilder why = new StringBuilder();
                NativeTokenStore n = NativeTokenStore.detect(why::append);
                if (n == null) throw new IllegalArgumentException("tokenStore.type native: " + why);
                n.migrateFrom(abs.resolve("auth.json"));
                yield n;
            }
            default -> multiUser ? file : new Lazy(() -> {
                NativeTokenStore n = NativeTokenStore.detect(reason ->
                        log.info("Tokens are kept in {}: {}", file.describe(), reason));
                if (n == null) return file;
                n.migrateFrom(abs.resolve("auth.json"));
                return n;
            });
        };
    }

    /** A store chosen on first use: {@code auto} probes the OS only when a token is needed. */
    static final class Lazy implements TokenStore {
        private final Supplier<TokenStore> choose;
        private volatile TokenStore store;

        Lazy(Supplier<TokenStore> choose) {
            this.choose = choose;
        }

        TokenStore store() {
            TokenStore s = store;
            if (s != null) return s;
            synchronized (this) {
                if (store == null) store = choose.get();
                return store;
            }
        }

        @Override
        public Optional<OAuthToken> get(String principalId, String name) {
            return store().get(principalId, name);
        }

        @Override
        public void put(String principalId, String name, OAuthToken token) {
            store().put(principalId, name, token);
        }

        @Override
        public boolean remove(String principalId, String name) {
            return store().remove(principalId, name);
        }

        @Override
        public List<String> names(String principalId) {
            return store().names(principalId);
        }

        @Override
        public void removeAll(String principalId) {
            store().removeAll(principalId);
        }

        @Override
        public boolean compareAndPut(String principalId, String name, OAuthToken expected, OAuthToken next) {
            return store().compareAndPut(principalId, name, expected, next);
        }

        @Override
        public <T> T locked(String principalId, String name, Supplier<T> action) {
            return store().locked(principalId, name, action);
        }

        @Override
        public boolean crossProcess() {
            return store().crossProcess();
        }

        @Override
        public String describe() {
            return store().describe();
        }
    }
}

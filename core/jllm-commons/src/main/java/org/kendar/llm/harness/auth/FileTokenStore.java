package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The standard {@link TokenStore}: one JSON file per principal.
 * <ul>
 *   <li>the single user and the shared sources (principal null): {@code <dir>/auth.json}, by default
 *       {@code <jllm.home or user.home>/.jllm/auth.json}, the same file and format as before;</li>
 *   <li>a user: {@code <dir>/users/<userId>.json}.</li>
 * </ul>
 * Writes are atomic (temp file and move), files are owner-only, and a lock file per file serializes threads and
 * processes ({@link #crossProcess()} is true). With a {@link TokenCipher} the files are AES-GCM encrypted; a file that
 * cannot be decrypted (tampered, wrong key) is reported once and read as empty, so its users log in again. A file
 * written with {@code JLLM_TOKEN_KEY_OLD} is rewritten with the current key when read. With a cipher, a plain file
 * is not trusted: it is reported and read as empty. Token contents are never logged.
 */
public final class FileTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(FileTokenStore.class);
    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9_@-][A-Za-z0-9._@-]{0,127}");
    private static final String ENVELOPE = "jllmEncrypted";

    /**
     * File locks belong to the whole JVM (a second {@code lock()} on the same file throws
     * {@link java.nio.channels.OverlappingFileLockException}), so every store on a file shares one in-process lock.
     */
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    /** Lock files the current thread already holds, across all stores: nested calls run without locking again. */
    private static final ThreadLocal<Set<Path>> HOLDING = ThreadLocal.withInitial(HashSet::new);
    private static final Map<Path, FileTokenStore> SHARED = new ConcurrentHashMap<>();

    private final Path sharedFile;
    private final Path usersDir;
    private final TokenCipher cipher;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Files already reported as unreadable: one log line each, not one per call. */
    private final Set<Path> reported = ConcurrentHashMap.newKeySet();

    /** @param cipher null: plain files (fine on a desktop, the user's own home) */
    public FileTokenStore(Path dir, TokenCipher cipher) {
        this(dir.resolve("auth.json"), dir.resolve("users"), cipher);
    }

    public FileTokenStore(Path dir) {
        this(dir, null);
    }

    private FileTokenStore(Path sharedFile, Path usersDir, TokenCipher cipher) {
        this.sharedFile = sharedFile.toAbsolutePath().normalize();
        this.usersDir = usersDir.toAbsolutePath().normalize();
        this.cipher = cipher;
    }

    /** A store whose single-user file is {@code file}; users go to {@code users/} next to it. */
    public static FileTokenStore ofFile(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return new FileTokenStore(abs, abs.resolveSibling("users"), null);
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("jllm.home", System.getProperty("user.home")), ".jllm");
    }

    public static Path defaultFile() {
        return defaultDir().resolve("auth.json");
    }

    /** The plain store on {@link #defaultDir()}, one instance for the whole JVM. */
    public static FileTokenStore defaultStore() {
        return SHARED.computeIfAbsent(defaultDir().toAbsolutePath().normalize(), d -> new FileTokenStore(d, null));
    }

    public boolean encrypted() {
        return cipher != null;
    }

    /** The file of a principal. @throws IllegalArgumentException for an id that is not file-safe */
    public Path fileOf(String principalId) {
        if (principalId == null) return sharedFile;
        if (!PRINCIPAL.matcher(principalId).matches()) {
            throw new IllegalArgumentException("Principal id not usable as a token file name");
        }
        return usersDir.resolve(principalId + ".json");
    }

    @Override
    public Optional<OAuthToken> get(String principalId, String name) {
        Path f = fileOf(principalId);
        return withFile(f, () -> Optional.ofNullable(readAll(f).get(name)));
    }

    @Override
    public void put(String principalId, String name, OAuthToken token) {
        Path f = fileOf(principalId);
        withFile(f, () -> {
            Map<String, OAuthToken> all = readAll(f);
            all.put(name, token);
            writeAll(f, all);
            return null;
        });
    }

    @Override
    public boolean remove(String principalId, String name) {
        Path f = fileOf(principalId);
        return withFile(f, () -> {
            Map<String, OAuthToken> all = readAll(f);
            boolean had = all.remove(name) != null;
            if (had) writeAll(f, all);
            return had;
        });
    }

    @Override
    public List<String> names(String principalId) {
        Path f = fileOf(principalId);
        return withFile(f, () -> readAll(f).keySet().stream().sorted().toList());
    }

    /** Deletes the user's file. */
    @Override
    public void removeAll(String principalId) {
        Path f = fileOf(principalId);
        withFile(f, () -> {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot delete " + f, e);
            }
            return null;
        });
    }

    @Override
    public <T> T locked(String principalId, String name, Supplier<T> action) {
        return withFile(fileOf(principalId), action);
    }

    @Override
    public boolean crossProcess() {
        return true;
    }

    @Override
    public String describe() {
        return "file " + sharedFile.getParent() + (cipher != null ? " (encrypted)" : "");
    }

    /**
     * Runs {@code action} holding the in-process lock and the inter-process file lock of {@code file}; reads inside
     * see other processes' writes. Re-entrant on the same thread, also across stores on the same file.
     */
    private <T> T withFile(Path file, Supplier<T> action) {
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        Set<Path> held = HOLDING.get();
        if (held.contains(lockFile)) return action.get();
        ReentrantLock lock = LOCKS.computeIfAbsent(lockFile, k -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock l = ch.lock()) {
                held.add(lockFile);
                try {
                    return action.get();
                } finally {
                    held.remove(lockFile);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot lock " + file, e);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, OAuthToken> readAll(Path file) {
        Map<String, OAuthToken> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        JsonNode root;
        try {
            root = mapper.readTree(file.toFile());
        } catch (IOException e) {
            report(file, "not readable JSON");
            return out;
        }
        if (root == null || !root.isObject()) return out;
        if (root.has(ENVELOPE)) {
            if (cipher == null) {
                report(file, "encrypted, but no " + TokenCipher.KEY_ENV + " is set");
                return out;
            }
            TokenCipher.Sealed sealed = new TokenCipher.Sealed(root.path("kid").asText(""),
                    root.path("nonce").asText(""), root.path("data").asText(""));
            try {
                root = mapper.readTree(cipher.decrypt(sealed));
            } catch (TokenCipher.DecryptException | IOException e) {
                report(file, e instanceof TokenCipher.DecryptException ? e.getMessage() : "not valid after decryption");
                return out;
            }
            parse(root, out);
            if (cipher.isOldKey(sealed)) {
                writeAll(file, out); // key rotation: rewrite with the current key
                log.info("Token file {} re-encrypted with the current key", file);
            }
            return out;
        }
        if (cipher != null) {
            if (!root.isEmpty()) report(file, "not encrypted, while encryption is on");
            return out;
        }
        parse(root, out);
        return out;
    }

    private void report(Path file, String why) {
        if (reported.add(file)) log.warn("Token file {} ignored ({}): its logins must be done again", file, why);
    }

    private static void parse(JsonNode root, Map<String, OAuthToken> out) {
        root.fields().forEachRemaining(e -> {
            JsonNode n = e.getValue();
            Map<String, String> extra = new HashMap<>();
            n.path("extra").fields().forEachRemaining(x -> extra.put(x.getKey(), x.getValue().asText()));
            out.put(e.getKey(), new OAuthToken(n.path("access").asText(), n.hasNonNull("refresh")
                    ? n.get("refresh").asText() : null, n.path("expiresAt").asLong(0), extra));
        });
    }

    private void writeAll(Path file, Map<String, OAuthToken> all) {
        ObjectNode root = mapper.createObjectNode();
        all.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> {
            OAuthToken t = en.getValue();
            ObjectNode n = root.putObject(en.getKey());
            n.put("access", t.access());
            if (t.refresh() != null) n.put("refresh", t.refresh());
            n.put("expiresAt", t.expiresAt());
            ObjectNode x = n.putObject("extra");
            t.extra().forEach(x::put);
        });
        try {
            String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            if (cipher != null) {
                TokenCipher.Sealed s = cipher.encrypt(text);
                ObjectNode env = mapper.createObjectNode();
                env.put(ENVELOPE, 1);
                env.put("kid", s.kid());
                env.put("nonce", s.nonce());
                env.put("data", s.data());
                text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(env);
            }
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "auth", ".tmp");
            restrict(tmp);
            Files.writeString(tmp, text);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            reported.remove(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** Owner-only: POSIX 600, or an ACL with the owner alone on Windows. Best effort. */
    static void restrict(Path p) {
        try {
            if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            } else {
                AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
                if (acl != null) {
                    acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
                }
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // the file system offers no permissions
        }
    }

    @Override
    public String toString() {
        return "FileTokenStore[" + describe() + "]";
    }
}

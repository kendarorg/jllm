package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Tokens per provider name in one JSON file (default {@code <jllm.home or user.home>/.jllm/auth.json}). Writes are
 * atomic (temp file and move), the file is owner-only, and a lock file serializes processes. Contents are never logged.
 */
public final class TokenStore {

    /**
     * File locks belong to the whole JVM (a second {@code lock()} on the same file throws
     * {@link java.nio.channels.OverlappingFileLockException}), so every store on a file shares one in-process lock.
     */
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    /** Lock files the current thread already holds, across all stores: nested calls run without locking again. */
    private static final ThreadLocal<Set<Path>> HOLDING = ThreadLocal.withInitial(HashSet::new);
    private static final Map<Path, TokenStore> SHARED = new ConcurrentHashMap<>();

    private final Path file;
    private final Path lockFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public TokenStore(Path file) {
        this.file = file;
        this.lockFile = file.toAbsolutePath().normalize().resolveSibling(file.getFileName() + ".lock");
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("jllm.home", System.getProperty("user.home")), ".jllm", "auth.json");
    }

    /** The store on {@link #defaultFile()}, one instance per file for the whole JVM. */
    public static TokenStore defaultStore() {
        return SHARED.computeIfAbsent(defaultFile().toAbsolutePath().normalize(), TokenStore::new);
    }

    public Optional<OAuthToken> get(String name) {
        return locked(() -> Optional.ofNullable(readAll().get(name)));
    }

    public void put(String name, OAuthToken token) {
        locked(() -> {
            Map<String, OAuthToken> all = readAll();
            all.put(name, token);
            writeAll(all);
            return null;
        });
    }

    public boolean remove(String name) {
        return locked(() -> {
            Map<String, OAuthToken> all = readAll();
            boolean had = all.remove(name) != null;
            if (had) writeAll(all);
            return had;
        });
    }

    public List<String> names() {
        return locked(() -> readAll().keySet().stream().sorted().toList());
    }

    /**
     * Runs {@code action} holding the in-process lock and the inter-process file lock; reads inside see other
     * processes' writes. Re-entrant on the same thread, also across stores on the same file.
     */
    public <T> T locked(Supplier<T> action) {
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

    private Map<String, OAuthToken> readAll() {
        Map<String, OAuthToken> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            JsonNode root = mapper.readTree(file.toFile());
            root.fields().forEachRemaining(e -> {
                JsonNode n = e.getValue();
                Map<String, String> extra = new HashMap<>();
                n.path("extra").fields().forEachRemaining(x -> extra.put(x.getKey(), x.getValue().asText()));
                out.put(e.getKey(), new OAuthToken(n.path("access").asText(), n.hasNonNull("refresh")
                        ? n.get("refresh").asText() : null, n.path("expiresAt").asLong(0), extra));
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private void writeAll(Map<String, OAuthToken> all) {
        ObjectNode root = mapper.createObjectNode();
        all.forEach((k, t) -> {
            ObjectNode n = root.putObject(k);
            n.put("access", t.access());
            if (t.refresh() != null) n.put("refresh", t.refresh());
            n.put("expiresAt", t.expiresAt());
            ObjectNode x = n.putObject("extra");
            t.extra().forEach(x::put);
        });
        try {
            Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "auth", ".tmp");
            restrict(tmp);
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** Owner-only: POSIX 600, or an ACL with the owner alone on Windows. Best effort. */
    private static void restrict(Path p) {
        try {
            if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            } else {
                AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
                if (acl != null) {
                    acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                            .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).build()));
                }
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // the file system offers no permissions
        }
    }
}

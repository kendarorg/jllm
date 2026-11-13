package org.kendar.llm.harness.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/** Stores the documents as {@code <root>/<chatId>/state/<name>.json}, next to the chat history. */
public class FileStateStore implements StateStore {

    private final Function<String, Path> chatRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileStateStore(Path root) {
        this.chatRoot = id -> root.resolve(safe(id));
    }

    /** {@code chatRoot(chatId)/state/<name>.json}: the directory of the chat, resolved at each use. */
    public FileStateStore(Function<String, Path> chatRoot) {
        this.chatRoot = chatRoot;
    }

    @Override
    public JsonNode load(String chatId, String name) {
        Path f = file(chatId, name);
        ReentrantLock lock = lockOf(f);
        lock.lock();
        try {
            if (!Files.isRegularFile(f)) return null;
            return mapper.readTree(f.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + f, e);
        } finally {
            lock.unlock();
        }
    }

    /** One lock per document file, shared by every store of the process (several stores may share a root). */
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private static ReentrantLock lockOf(Path f) {
        return LOCKS.computeIfAbsent(f.toAbsolutePath().normalize(), k -> new ReentrantLock());
    }

    /**
     * Writes a unique temporary file in the same directory, then moves it over the document (atomically when the file
     * system can), under the document's lock: concurrent saves never share a temporary file or interleave.
     */
    @Override
    public void save(String chatId, String name, JsonNode value) {
        Path f = file(chatId, name);
        ReentrantLock lock = lockOf(f);
        lock.lock();
        Path tmp = null;
        try {
            Files.createDirectories(f.getParent());
            tmp = Files.createTempFile(f.getParent(), f.getFileName().toString(), ".tmp");
            mapper.writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + f, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            lock.unlock();
        }
    }

    @Override
    public void delete(String chatId) {
        Path dir = chatRoot.apply(chatId).resolve("state");
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete state of chat " + chatId, e);
        }
    }

    private Path file(String chatId, String name) {
        return chatRoot.apply(chatId).resolve("state").resolve(safe(name) + ".json");
    }

    private static String safe(String id) {
        String s = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        return s.isEmpty() ? "_" : s;
    }
}

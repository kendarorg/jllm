package org.kendar.llm.harness.tools;

import org.kendar.llm.harness.sandbox.SandboxExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Caps the total size of a chat's storage ({@code storage.maxBytes}). The file tools ask it before every write.
 * A running counter per chat is computed once, by walking the directory, the first time the chat writes; writes that
 * bypass the tools (a shell command) are not counted until the next start. A rejected write changes nothing.
 */
public final class StorageQuota {

    public static final StorageQuota UNLIMITED = new StorageQuota(-1, null);

    private final long maxBytes;
    private final SandboxExecutor sandbox;
    private final Map<String, Long> used = new ConcurrentHashMap<>();

    /** @param maxBytes {@code -1} (or any negative value): no limit */
    public StorageQuota(long maxBytes, SandboxExecutor sandbox) {
        this.maxBytes = maxBytes;
        this.sandbox = sandbox;
    }

    public boolean enabled() {
        return maxBytes >= 0;
    }

    /**
     * A write on the host file system: {@code target} goes from {@code oldSize} to {@code newSize} bytes. Targets
     * outside the chat storage are not counted.
     *
     * @throws IOException when the new total would exceed the limit; nothing is recorded then
     */
    public synchronized void reserve(String chatId, Path target, long oldSize, long newSize) throws IOException {
        if (!enabled() || sandbox == null) return;
        Path root = sandbox.chatStorage(chatId).toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(root)) return;
        reserveDelta(chatId, root, newSize - oldSize);
    }

    /** A write inside a container, where the directory cannot be walked: counted from zero per process. */
    public synchronized void reserveRemote(String chatId, long delta) throws IOException {
        if (!enabled()) return;
        reserveDelta(chatId, null, delta);
    }

    private void reserveDelta(String chatId, Path root, long delta) throws IOException {
        long current = used.computeIfAbsent(chatId, c -> root == null ? 0L : size(root));
        if (delta > 0 && current + delta > maxBytes) {
            throw new IOException("Storage quota exceeded: this write needs " + delta + " more bytes, the chat uses "
                    + current + " of " + maxBytes + " allowed (storage.maxBytes)");
        }
        used.put(chatId, Math.max(0, current + delta));
    }

    /** Bytes counted for the chat (computes the baseline when needed). */
    public synchronized long used(String chatId) {
        if (!enabled() || sandbox == null) return 0;
        return used.computeIfAbsent(chatId, c -> size(sandbox.chatStorage(c).toAbsolutePath().normalize()));
    }

    /** Forget the counter of a chat (deleted chat). */
    public synchronized void forget(String chatId) {
        used.remove(chatId);
    }

    private static long size(Path root) {
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}

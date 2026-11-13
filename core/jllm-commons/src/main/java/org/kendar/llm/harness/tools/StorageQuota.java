package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
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
    /** Chat id to its storage directory; {@code null}: nothing is counted. */
    private final Function<String, Path> chatStorage;
    private final Map<String, Long> used = new ConcurrentHashMap<>();

    /**
     * @param maxBytes {@code -1} (or any negative value): no limit
     * @param chatStorage chat id to its storage directory (e.g. {@code sandbox::chatStorage}); {@code null}: nothing is counted
     */
    public StorageQuota(long maxBytes, Function<String, Path> chatStorage) {
        this.maxBytes = maxBytes;
        this.chatStorage = chatStorage;
    }

    public boolean enabled() {
        return maxBytes >= 0;
    }

    /** A recorded change of the counter; {@link #rollback} takes it back when the write then fails. */
    public interface Reservation {
        Reservation NONE = () -> {
        };

        /** Takes the change back; only the first call counts. */
        void rollback();
    }

    /**
     * A write on the host file system: {@code target} goes from {@code oldSize} to {@code newSize} bytes. Targets
     * outside the chat storage are not counted. Call {@link Reservation#rollback} when the write then fails.
     *
     * @throws IOException when {@code target} is a directory, or the new total would exceed the limit; nothing is
     *                     recorded then
     */
    public synchronized Reservation reserve(String chatId, Path target, long oldSize, long newSize) throws IOException {
        if (Files.isDirectory(target)) throw new IOException("Is a directory: " + target);
        if (!enabled() || chatStorage == null) return Reservation.NONE;
        Path root = chatStorage.apply(chatId).toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(root)) return Reservation.NONE;
        return reserveDelta(chatId, root, newSize - oldSize);
    }

    /**
     * A write inside a container, where the directory cannot be walked: counted from zero per process. Call
     * {@link Reservation#rollback} when the write then fails.
     */
    public synchronized Reservation reserveRemote(String chatId, long delta) throws IOException {
        if (!enabled()) return Reservation.NONE;
        return reserveDelta(chatId, null, delta);
    }

    private Reservation reserveDelta(String chatId, Path root, long delta) throws IOException {
        long current = used.computeIfAbsent(chatId, c -> root == null ? 0L : size(root));
        if (delta > 0 && current + delta > maxBytes) {
            throw new IOException("Storage quota exceeded: this write needs " + delta + " more bytes, the chat uses "
                    + current + " of " + maxBytes + " allowed (storage.maxBytes)");
        }
        long next = Math.max(0, current + delta);
        used.put(chatId, next);
        long applied = next - current;
        if (applied == 0) return Reservation.NONE;
        boolean[] done = {false};
        return () -> {
            synchronized (StorageQuota.this) {
                if (done[0]) return;
                done[0] = true;
                Long now = used.get(chatId);
                if (now != null) used.put(chatId, Math.max(0, now - applied));
            }
        };
    }

    /** Bytes counted for the chat (computes the baseline when needed). */
    public synchronized long used(String chatId) {
        if (!enabled() || chatStorage == null) return 0;
        return used.computeIfAbsent(chatId, c -> size(chatStorage.apply(c).toAbsolutePath().normalize()));
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

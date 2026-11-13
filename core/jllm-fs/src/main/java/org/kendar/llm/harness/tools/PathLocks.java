package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Locks held by the file tools across a read-modify-write of one file and its checkpoint backup, so two parallel
 * {@code edit_file}/{@code write_file} calls on the same file run one after the other. Keyed by the real path (any
 * spelling or link to the same file shares the lock, also across chats sharing a directory); an entry is removed when
 * nobody holds or waits for it.
 */
final class PathLocks {

    private static final ConcurrentHashMap<Object, Entry> LOCKS = new ConcurrentHashMap<>();

    private PathLocks() {
    }

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    /** A held lock; {@link #close} releases it. */
    interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /** Locks a host file, by its real path (the deepest existing ancestor resolved, the rest appended). */
    static Held lock(Path file) {
        return lockKey(realKey(file));
    }

    /** Locks a key that is not a host path (e.g. a file in a container). */
    static Held lockKey(Object key) {
        Entry e = LOCKS.compute(key, (k, v) -> {
            Entry entry = v == null ? new Entry() : v;
            entry.users++;
            return entry;
        });
        e.lock.lock();
        return () -> {
            e.lock.unlock();
            LOCKS.computeIfPresent(key, (k, v) -> --v.users == 0 ? null : v);
        };
    }

    static Path realKey(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return abs;
        try {
            return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
        } catch (IOException e) {
            return abs;
        }
    }

    /** Number of keys currently held or waited for (tests). */
    static int size() {
        return LOCKS.size();
    }
}

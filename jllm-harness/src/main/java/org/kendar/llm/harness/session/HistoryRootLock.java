package org.kendar.llm.harness.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One process per history root: holds an exclusive lock on {@code <root>/.lock} until closed (or until the process
 * ends). Without it two instances overwrite each other's {@code session.json}.
 */
public final class HistoryRootLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private HistoryRootLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** @throws IllegalStateException when another process holds the root, naming the lock file */
    public static HistoryRootLock acquire(Path root) {
        Path file = root.toAbsolutePath().normalize().resolve(".lock");
        FileChannel ch = null;
        try {
            Files.createDirectories(file.getParent());
            ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock l = ch.tryLock();
            if (l == null) throw busy(file);
            return new HistoryRootLock(ch, l);
        } catch (OverlappingFileLockException e) {
            closeQuietly(ch);
            throw busy(file);
        } catch (IOException e) {
            closeQuietly(ch);
            throw new UncheckedIOException("Cannot lock " + file, e);
        } catch (RuntimeException e) {
            closeQuietly(ch);
            throw e;
        }
    }

    private static IllegalStateException busy(Path file) {
        return new IllegalStateException("The history directory is in use by another process (lock " + file
                + "). Stop it, or use another --workdir.");
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) return;
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
        }
        closeQuietly(channel);
    }
}

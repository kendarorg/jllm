package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Replaces the content of a file without a moment where it is truncated or half written: the new content goes to a
 * temporary file in the same directory, is flushed to disk and then moved over the target.
 * <ul>
 *     <li>a symlink is kept: the file it points to is replaced</li>
 *     <li>a file with more than one hard link is rewritten in place, so all the links keep sharing it</li>
 *     <li>POSIX permissions / DOS hidden, system and archive flags of the old file are copied to the new one</li>
 *     <li>without atomic move support the move is not atomic; when the target stays locked (Windows
 *     {@code AccessDenied}, e.g. an open handle) after a few retries the file is rewritten in place</li>
 * </ul>
 */
public final class AtomicFiles {

    private static final int MOVE_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 50;

    private AtomicFiles() {
    }

    private interface Content {
        void writeTo(FileChannel ch) throws IOException;
    }

    /** Replaces (or creates) {@code target} with {@code data}. The parent directory must exist. */
    public static void write(Path target, byte[] data) throws IOException {
        replace(target, ch -> {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) ch.write(b);
        });
    }

    /** Replaces (or creates) {@code target} with the content of {@code source}. The parent directory must exist. */
    public static void copy(Path source, Path target) throws IOException {
        replace(target, ch -> {
            try (FileChannel src = FileChannel.open(source, StandardOpenOption.READ)) {
                long size = src.size();
                for (long pos = 0; pos < size; ) {
                    long n = src.transferTo(pos, size - pos, ch);
                    if (n <= 0) break;
                    pos += n;
                }
            }
        });
    }

    private static void replace(Path target, Content content) throws IOException {
        Path real = Files.isSymbolicLink(target) ? target.toRealPath() : target.toAbsolutePath().normalize();
        if (Files.isDirectory(real)) throw new IOException("Is a directory: " + target);
        boolean exists = Files.exists(real);
        if (exists && linkCount(real) > 1) {
            inPlace(real, content);
            return;
        }
        Path tmp = createTemp(real);
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                content.writeTo(ch);
                ch.force(true);
            }
            if (exists) copyAttributes(real, tmp);
            move(tmp, real, content);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** A new empty file next to {@code real}, created with the default permissions (not the 0600 of temp files). */
    private static Path createTemp(Path real) throws IOException {
        Path dir = real.getParent();
        for (int i = 0; ; i++) {
            Path tmp = dir.resolve("." + real.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong())
                    + ".tmp");
            try {
                Files.newByteChannel(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
                return tmp;
            } catch (FileAlreadyExistsException e) {
                if (i >= 10) throw e;
            }
        }
    }

    private static void move(Path tmp, Path real, Content content) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(tmp, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, real, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= MOVE_ATTEMPTS || !Files.exists(real)) {
                    inPlace(real, content);
                    return;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    inPlace(real, content);
                    return;
                }
            }
        }
    }

    private static void inPlace(Path real, Content content) throws IOException {
        try (FileChannel ch = FileChannel.open(real, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            content.writeTo(ch);
            ch.force(true);
        }
    }

    private static int linkCount(Path p) {
        try {
            Object n = Files.getAttribute(p, "unix:nlink");
            return n instanceof Integer i ? i : 1;
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return 1;
        }
    }

    private static void copyAttributes(Path from, Path to) {
        try {
            if (Files.getFileAttributeView(from, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
                return;
            }
            DosFileAttributeView src = Files.getFileAttributeView(from, DosFileAttributeView.class);
            DosFileAttributeView dst = Files.getFileAttributeView(to, DosFileAttributeView.class);
            if (src != null && dst != null) {
                DosFileAttributes a = src.readAttributes();
                if (a.isHidden()) dst.setHidden(true);
                if (a.isSystem()) dst.setSystem(true);
                dst.setArchive(a.isArchive());
            }
        } catch (IOException | UnsupportedOperationException e) {
            // best effort: the content matters more than the flags
        }
    }
}

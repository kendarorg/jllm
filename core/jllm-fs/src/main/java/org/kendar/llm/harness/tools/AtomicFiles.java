package org.kendar.llm.harness.tools;

import org.kendar.llm.harness.tools.PathJail.Dir;
import org.kendar.llm.harness.tools.PathJail.JailedPath;
import org.kendar.llm.harness.tools.PathJail.Located;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Replaces the content of a file inside a {@link PathJail} without a moment where it is truncated or half written: the
 * new content goes to a temporary file in the same directory (created through the jail, so never through a swapped
 * link), is flushed to disk and then moved over the target, relative to the open directory.
 * <ul>
 *     <li>a final link inside the jail is resolved by the jail: the file it points to is replaced</li>
 *     <li>a file with more than one hard link is rewritten in place, so all the links keep sharing it; when the file
 *     belongs to another user the write is refused (see {@link PathJail})</li>
 *     <li>POSIX permissions / DOS hidden, system and archive flags of the old file are copied to the new one</li>
 *     <li>without atomic move support the move is not atomic; when the target stays locked (Windows
 *     {@code AccessDenied}, e.g. an open handle) after a few retries the file is rewritten in place, opened without
 *     following links</li>
 * </ul>
 */
public final class AtomicFiles {

    private static final int MOVE_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 50;

    private AtomicFiles() {
    }

    /** Writes the new content to an open channel. */
    @FunctionalInterface
    private interface Content {
        void writeTo(SeekableByteChannel ch) throws IOException;
    }

    /** Called with the number of bytes written, after the temporary file is complete and before it replaces the target. */
    @FunctionalInterface
    public interface BeforeCommit {
        void check(long size) throws IOException;
    }

    /** Replaces (or creates) {@code target} with {@code data}. The parent directory must exist. */
    public static void write(JailedPath target, byte[] data) throws IOException {
        replace(target, ch -> {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) ch.write(b);
        }, null);
    }

    /** Replaces (or creates) {@code target} with the content of {@code source}. The parent directory must exist. */
    public static void write(JailedPath target, InputStream source) throws IOException {
        write(target, source, null);
    }

    /** As {@link #write(JailedPath, InputStream)}, with a check before the temporary file replaces the target. */
    public static void write(JailedPath target, InputStream source, BeforeCommit before) throws IOException {
        long[] size = new long[1];
        replace(target, ch -> {
            byte[] buf = new byte[65536];
            for (int n; (n = source.read(buf)) >= 0; ) {
                ByteBuffer b = ByteBuffer.wrap(buf, 0, n);
                while (b.hasRemaining()) ch.write(b);
                size[0] += n;
            }
        }, before == null ? null : () -> before.check(size[0]));
    }

    @FunctionalInterface
    private interface Commit {
        void run() throws IOException;
    }

    private static void replace(JailedPath target, Content content, Commit before) throws IOException {
        try (Located l = PathJail.locate(target, false, true, true)) {
            Dir dir = l.dir();
            String name = l.name();
            java.nio.file.attribute.BasicFileAttributes old = dir.stat(name);
            if (old != null && old.isDirectory()) throw new IOException("Is a directory: " + target);
            boolean exists = old != null;
            if (exists && linkCount(dir, name) > 1) {
                inPlace(dir, name, before == null ? content : ch -> {
                    content.writeTo(ch);
                    before.run();
                }, target);
                return;
            }
            String tmp = createTemp(dir, name);
            boolean moved = false;
            try {
                try (SeekableByteChannel ch = dir.open(tmp, Set.of(StandardOpenOption.WRITE))) {
                    content.writeTo(ch);
                    if (ch instanceof FileChannel fc) fc.force(true);
                }
                if (before != null) before.run();
                if (exists) copyAttributes(dir, name, tmp);
                PathJail.hook();
                moved = move(dir, tmp, name, content, target);
            } finally {
                if (!moved) deleteQuietly(dir, tmp);
            }
        }
    }

    /** A new empty file next to {@code name}, created with the default permissions (not the 0600 of temp files). */
    private static String createTemp(Dir dir, String name) throws IOException {
        for (int i = 0; ; i++) {
            String tmp = "." + name + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp";
            try {
                PathJail.hook();
                dir.open(tmp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).close();
                return tmp;
            } catch (FileAlreadyExistsException e) {
                if (i >= 10) throw e;
            }
        }
    }

    /** @return true when the temporary file was moved over the target (false: rewritten in place, the temp file stays) */
    private static boolean move(Dir dir, String tmp, String name, Content content, JailedPath target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                dir.rename(tmp, name);
                return true;
            } catch (AccessDeniedException e) {
                if (attempt >= MOVE_ATTEMPTS || dir.stat(name) == null) {
                    copyTempInPlace(dir, tmp, name, target);
                    return false;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    copyTempInPlace(dir, tmp, name, target);
                    return false;
                }
            }
        }
    }

    /** The target stays locked: its content is replaced by the finished temporary file's. */
    private static void copyTempInPlace(Dir dir, String tmp, String name, JailedPath target) throws IOException {
        PathJail.refuseForeignHardLink(dir, name, target.lexical().toString());
        try (SeekableByteChannel in = dir.open(tmp, Set.of(StandardOpenOption.READ));
             SeekableByteChannel out = dir.open(name, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            ByteBuffer b = ByteBuffer.allocate(65536);
            while (in.read(b) >= 0) {
                b.flip();
                while (b.hasRemaining()) out.write(b);
                b.clear();
            }
            if (out instanceof FileChannel fc) fc.force(true);
        }
    }

    private static void inPlace(Dir dir, String name, Content content, JailedPath target) throws IOException {
        PathJail.refuseForeignHardLink(dir, name, target.lexical().toString());
        PathJail.hook();
        try (SeekableByteChannel ch = dir.open(name, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            content.writeTo(ch);
            if (ch instanceof FileChannel fc) fc.force(true);
        }
    }

    private static void deleteQuietly(Dir dir, String name) {
        try {
            if (dir.stat(name) != null) dir.delete(name, false);
        } catch (IOException e) {
            // best effort: a leftover temporary file is harmless
        }
    }

    private static int linkCount(Dir dir, String name) {
        if (PathJail.WINDOWS) return 1;
        try {
            Object n = Files.getAttribute(dir.path().resolve(name), "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return n instanceof Integer i ? i : 1;
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return 1;
        }
    }

    private static void copyAttributes(Dir dir, String from, String to) {
        try {
            PosixFileAttributeView src = dir.view(from, PosixFileAttributeView.class);
            PosixFileAttributeView dst = dir.view(to, PosixFileAttributeView.class);
            if (src != null && dst != null) {
                dst.setPermissions(src.readAttributes().permissions());
                return;
            }
            DosFileAttributeView dsrc = dir.view(from, DosFileAttributeView.class);
            DosFileAttributeView ddst = dir.view(to, DosFileAttributeView.class);
            if (dsrc != null && ddst != null) {
                DosFileAttributes a = dsrc.readAttributes();
                if (a.isHidden()) ddst.setHidden(true);
                if (a.isSystem()) ddst.setSystem(true);
                ddst.setArchive(a.isArchive());
            }
        } catch (IOException | UnsupportedOperationException e) {
            // best effort: the content matters more than the flags
        }
    }
}

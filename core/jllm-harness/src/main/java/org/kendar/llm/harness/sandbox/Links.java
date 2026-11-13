package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Links that must never be followed when deleting or packing a tree: symbolic links and Windows junctions. Java
 * reports a junction as a directory (and as "other"), so {@link Files#walk} descends into it; these helpers do not.
 */
final class Links {

    private Links() {
    }

    /** True when {@code p} exists and is a symbolic link or a junction; false for a missing path. */
    static boolean isLink(Path p) {
        try {
            return isLink(p, Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code a}: the attributes of {@code p}, read without following links. A junction is a directory reparse point
     * (Java: {@code isOther()}) that resolves somewhere else; other reparse directories (cloud placeholders) resolve to
     * themselves and are ordinary directories.
     */
    static boolean isLink(Path p, BasicFileAttributes a) {
        if (a.isSymbolicLink()) return true;
        if (!(a.isOther() && a.isDirectory())) return false;
        try {
            Path parent = p.toAbsolutePath().getParent();
            Path lexical = parent == null ? p.toAbsolutePath() : parent.toRealPath().resolve(p.getFileName());
            return !p.toRealPath().equals(lexical);
        } catch (IOException e) {
            return true; // dangling junction
        }
    }

    /** Deletes {@code dir} and everything below it; links (junctions included) are removed, never followed. */
    static void deleteTree(Path dir) throws IOException {
        BasicFileAttributes top;
        try {
            top = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return;
        }
        if (!top.isDirectory() || isLink(dir, top)) {
            Files.deleteIfExists(dir);
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                if (!d.equals(dir) && isLink(d, a)) {
                    Files.deleteIfExists(d); // removes the junction itself, not its target
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Links, directory swaps and the {@link PathJail} test seam, for the jail tests. */
final class JailTestSupport {

    private JailTestSupport() {
    }

    /** A symbolic link to a directory; on Windows, without the privilege, a junction. False when neither works. */
    static boolean linkDir(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // not permitted: a junction needs no privilege on Windows
        }
        if (!PathResolver.WINDOWS) return false;
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.exists(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A symbolic link to a file (on Windows it needs the symlink privilege). */
    static boolean linkFile(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    /** Whether this machine lets the tests create directory links. */
    static boolean canLinkDirs(Path scratch) throws IOException {
        Path target = Files.createDirectories(scratch.resolve("probe-target"));
        Path link = scratch.resolve("probe-link");
        boolean ok = linkDir(link, target);
        if (ok) removeLink(link);
        return ok;
    }

    static boolean canLinkFiles(Path scratch) throws IOException {
        Path target = Files.writeString(scratch.resolve("probe-file"), "x");
        Path link = scratch.resolve("probe-file-link");
        boolean ok = linkFile(link, target);
        if (ok) Files.deleteIfExists(link);
        return ok;
    }

    /** Removes a link (a junction too) without touching its target. */
    static void removeLink(Path link) throws IOException {
        Files.deleteIfExists(link);
    }

    /** The directory is moved aside and a link to {@code target} takes its place, as an attacker in the host would. */
    static void swapDirForLink(Path dir, Path target) {
        try {
            Files.move(dir, dir.resolveSibling(dir.getFileName() + ".moved"));
            if (!linkDir(dir, target)) throw new IllegalStateException("cannot link " + dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The file is removed and a link to {@code target} takes its place. */
    static void swapFileForLink(Path file, Path target) {
        try {
            Files.delete(file);
            if (!linkFile(file, target)) throw new IllegalStateException("cannot link " + file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The regular files below {@code dir}, relative and sorted; links are not entered. */
    static List<String> files(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .forEach(p -> out.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        out.sort(null);
        return out;
    }

    /** The strategies this machine can run: verify always, secure where the file system has secure streams. */
    static Stream<PathJail.Strategy> strategies() {
        List<PathJail.Strategy> out = new ArrayList<>(List.of(PathJail.Strategy.VERIFY));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")))) {
            if (ds instanceof SecureDirectoryStream) out.add(PathJail.Strategy.SECURE);
        } catch (IOException e) {
            // verify only
        }
        return out.stream();
    }

    /** Runs {@code action} the {@code n}th time (1-based) the jail reaches an open, create, rename or delete. */
    static void onNth(int n, Runnable action) {
        AtomicInteger calls = new AtomicInteger();
        PathJail.beforeOpen = () -> {
            if (calls.incrementAndGet() == n) action.run();
        };
    }

    static void reset() {
        PathJail.beforeOpen = null;
        PathJail.forced = null;
    }
}

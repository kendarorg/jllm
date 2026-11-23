package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.kendar.llm.harness.tools.PathResolver.JailedPath;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Which strategy is chosen where, and one functional suite that passes under every strategy the machine supports. */
class PathJailStrategyTest {

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        JailTestSupport.reset();
    }

    static java.util.stream.Stream<PathJail.Strategy> strategies() {
        return JailTestSupport.strategies();
    }

    @Test
    void theStrategyFollowsWhatTheFileSystemSupports() throws Exception {
        Path real = tmp.toRealPath();
        boolean secure;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(real)) {
            secure = ds instanceof SecureDirectoryStream;
        }
        assertEquals(secure ? PathJail.Strategy.SECURE : PathJail.Strategy.VERIFY, PathJail.strategy(real));
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertEquals(PathJail.Strategy.VERIFY, PathJail.strategy(real), "Windows has no secure directory streams");
        } else if (os.contains("linux") || os.contains("mac")) {
            assertEquals(PathJail.Strategy.SECURE, PathJail.strategy(real), "Linux and macOS: openat and O_NOFOLLOW");
        }
    }

    private static JailedPath at(Path root, String path) {
        return PathResolver.resolve(root, path, ExternalLinks.NONE, false);
    }

    private static String readString(JailedPath p) throws IOException {
        try (var in = PathJail.newInputStream(p)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeString(JailedPath p, String s, java.nio.file.OpenOption... options) throws IOException {
        try (OutputStream out = Channels.newOutputStream(PathJail.openWrite(p, Set.of(options)))) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void readWriteAppendReplaceCreateDeleteAndInsideLinks(PathJail.Strategy s) throws Exception {
        PathJail.forced = s;
        Path root = Files.createDirectories(tmp.resolve("work"));

        // create directories, write, read
        JailedPath f = at(root, "a/b/c.txt");
        PathJail.createParentDirectories(f);
        writeString(f, "one", StandardOpenOption.CREATE_NEW);
        assertEquals("one", readString(f));
        assertTrue(PathJail.isDirectory(at(root, "a/b")));
        assertNull(PathJail.stat(at(root, "a/missing.txt")));
        assertThrows(FileAlreadyExistsException.class, () -> writeString(f, "x", StandardOpenOption.CREATE_NEW));

        // append
        writeString(f, "+two", StandardOpenOption.APPEND);
        assertEquals("one+two", readString(f));

        // replace atomically, no temp file left
        AtomicFiles.write(f, "three".getBytes(StandardCharsets.UTF_8));
        assertEquals("three", readString(f));
        assertEquals(java.util.List.of("c.txt"), PathJail.list(at(root, "a/b")).stream().map(PathJail.Entry::name).toList());
        AtomicFiles.write(at(root, "a/new.bin"), new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("a/new.bin")));

        // truncate on open
        writeString(f, "x", StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals("x", readString(f));

        // a directory is not a file
        assertThrows(IOException.class, () -> AtomicFiles.write(at(root, "a/b"), new byte[]{1}));

        // a link pointing inside is followed and kept
        String current = "x";
        if (JailTestSupport.canLinkFiles(tmp)) {
            Files.createSymbolicLink(root.resolve("alias.txt"), Path.of("a/b/c.txt"));
            assertEquals("x", readString(at(root, "alias.txt")));
            AtomicFiles.write(at(root, "alias.txt"), "via link".getBytes(StandardCharsets.UTF_8));
            assertTrue(Files.isSymbolicLink(root.resolve("alias.txt")));
            assertEquals("via link", Files.readString(root.resolve("a/b/c.txt")));
            BasicFileAttributes own = PathJail.statNoFollow(at(root, "alias.txt"));
            assertTrue(PathResolver.isLink(own));
            current = "via link";
        }
        if (JailTestSupport.canLinkDirs(tmp)) {
            assertTrue(JailTestSupport.linkDir(root.resolve("dirlink"), root.resolve("a")));
            assertEquals(current, readString(at(root, "dirlink/b/c.txt")));
        }

        // delete: a file, then a tree
        assertTrue(PathJail.deleteIfExists(f));
        assertFalse(PathJail.deleteIfExists(f));
        writeString(f, "again", StandardOpenOption.CREATE);
        if (JailTestSupport.canLinkDirs(tmp)) {
            JailTestSupport.removeLink(root.resolve("dirlink"));
        }
        PathJail.deleteTree(at(root, "a"));
        assertFalse(Files.exists(root.resolve("a")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void aLinkLeavingTheJailIsRefusedAndNeverFollowed(PathJail.Strategy s) throws Exception {
        PathJail.forced = s;
        assumeTrue(JailTestSupport.canLinkDirs(tmp), "links are not permitted on this machine");
        Path root = Files.createDirectories(tmp.resolve("work"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "TOP SECRET");
        assertTrue(JailTestSupport.linkDir(root.resolve("out"), outside));

        // refused when resolved (the early check) and, with a path that passed it, by the walk itself
        assertThrows(IllegalArgumentException.class, () -> at(root, "out/secret.txt"));
        JailedPath racing = new JailedPath(root.toAbsolutePath().normalize(), root.toRealPath(),
                java.util.List.of("out", "secret.txt"), root.toAbsolutePath().normalize().resolve("out/secret.txt"), ExternalLinks.NONE);
        assertThrows(IllegalArgumentException.class, () -> PathJail.newInputStream(racing));
        assertThrows(IllegalArgumentException.class, () -> PathJail.openWrite(racing, Set.of(StandardOpenOption.CREATE)));
        assertThrows(IllegalArgumentException.class, () -> PathJail.stat(racing));
        assertEquals(java.util.List.of("secret.txt"), JailTestSupport.files(outside));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void tooManyLevelsOfLinksAreRefused(PathJail.Strategy s) throws Exception {
        PathJail.forced = s;
        assumeTrue(JailTestSupport.canLinkFiles(tmp), "file links are not permitted on this machine");
        Path root = Files.createDirectories(tmp.resolve("work"));
        Files.writeString(root.resolve("target.txt"), "t");
        String prev = "target.txt";
        for (int i = 0; i < PathResolver.MAX_LINK_HOPS + 5; i++) {
            Files.createSymbolicLink(root.resolve("l" + i), Path.of(prev));
            prev = "l" + i;
        }
        // a short chain works, a chain over the cap is refused
        assertEquals("t", readString(at(root, "l5")));
        String longest = "l" + (PathResolver.MAX_LINK_HOPS + 4);
        // resolve already refuses it where the file system caps the chain itself; the walk caps it for a path that passed
        assertThrows(IllegalArgumentException.class, () -> readString(at(root, longest)));
        Path abs = root.toAbsolutePath().normalize();
        JailedPath passed = new JailedPath(abs, root.toRealPath(), java.util.List.of(longest), abs.resolve(longest), ExternalLinks.NONE);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> readString(passed));
        assertTrue(e.getMessage().contains("Too many levels of links"), e.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void aDanglingLinkIsRefused(PathJail.Strategy s) throws Exception {
        PathJail.forced = s;
        assumeTrue(JailTestSupport.canLinkFiles(tmp), "file links are not permitted on this machine");
        Path root = Files.createDirectories(tmp.resolve("work"));
        Files.createSymbolicLink(root.resolve("dangling"), Path.of("nowhere.txt"));

        assertThrows(IllegalArgumentException.class, () -> readString(at(root, "dangling")));
        assertThrows(IllegalArgumentException.class, () -> AtomicFiles.write(at(root, "dangling"), new byte[]{1}));
        assertFalse(Files.exists(root.resolve("nowhere.txt")));
    }

    @Test
    void aHardLinkOfTheSameOwnerKeepsSharing() throws Exception {
        assumeTrue(!PathResolver.WINDOWS, "the link count is not exposed on Windows: unchanged behaviour");
        Path root = Files.createDirectories(tmp.resolve("work"));
        Files.writeString(root.resolve("a.txt"), "v1");
        try {
            Files.createLink(root.resolve("b.txt"), root.resolve("a.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "hard links not supported here: " + e);
        }

        AtomicFiles.write(at(root, "a.txt"), "v2".getBytes(StandardCharsets.UTF_8));

        assertEquals("v2", Files.readString(root.resolve("b.txt")), "written in place: the other link sees it");
    }

    @Test
    void permissionsSurviveAnAtomicReplace() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("work"));
        Path f = Files.writeString(root.resolve("run.sh"), "#!/bin/sh\n");
        assumeTrue(Files.getFileAttributeView(f, java.nio.file.attribute.PosixFileAttributeView.class) != null,
                "POSIX permissions only");
        Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---"));

        AtomicFiles.write(at(root, "run.sh"), "#!/bin/sh\necho\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("rwxr-x---", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
    }
}

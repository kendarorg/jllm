package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.tools.PathResolver.JailedPath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Adversarial tests of {@link PathResolver} alone: nothing here opens a file through {@link PathJail}. */
class PathResolverTest {

    @TempDir
    Path tmp;
    Path root;
    Path outside;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("root"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createDirectories(root.resolve("sub/deeper"));
    }

    private JailedPath resolve(String p) {
        return PathResolver.resolve(root, p, ExternalLinks.NONE, false);
    }

    /** Either refused, or confined: the components are plain names and the real location is below the real root. */
    private void assertRefusedOrConfined(String p) {
        JailedPath jp;
        try {
            jp = resolve(p);
        } catch (IllegalArgumentException e) {
            return; // includes InvalidPathException
        }
        for (String c : jp.components()) {
            assertFalse(c.isEmpty() || c.equals(".") || c.equals(".."), "bad component in " + jp.components() + " for " + p);
        }
        assertTrue(jp.lexical().startsWith(jp.root()), p);
        assertTrue(PathResolver.realPath(jp).startsWith(jp.realRoot()), "lands outside: " + p);
    }

    // ── lexical ──────────────────────────────────────────────────────────────────────────────

    @Test
    void dotDotInsideIsNormalized() {
        JailedPath jp = resolve("sub/deeper/../../sub/./x.txt");
        assertEquals(List.of("sub", "x.txt"), jp.components());
    }

    @Test
    void dotDotEscapesRefused() {
        for (String p : new String[]{"..", "../outside/secret.txt", "sub/../../outside", "sub/deeper/../../../x",
                "./../x", "sub/..//..", "a/b/c/../../../../x"}) {
            assertThrows(IllegalArgumentException.class, () -> resolve(p), p);
        }
    }

    @Test
    void rootItselfAndEmptyPath() {
        assertTrue(resolve("").components().isEmpty());
        assertTrue(resolve(".").components().isEmpty());
        assertTrue(resolve("sub/..").components().isEmpty());
    }

    @Test
    void absolutePathsInsideAllowedOutsideRefused() {
        assertEquals(List.of("sub"), resolve(root.resolve("sub").toString()).components());
        assertThrows(IllegalArgumentException.class, () -> resolve(outside.resolve("secret.txt").toString()));
        assertThrows(IllegalArgumentException.class, () -> resolve(tmp.getRoot().toString()));
        // a sibling that merely shares the textual prefix of the root
        Path sibling = tmp.resolve("root-evil");
        assertThrows(IllegalArgumentException.class, () -> resolve(sibling.toString()));
    }

    @Test
    void mixedSeparators() {
        assertRefusedOrConfined("sub\\..\\..\\outside");
        assertRefusedOrConfined("sub/..\\../outside");
        assertRefusedOrConfined("sub\\deeper/x.txt");
        if (PathResolver.WINDOWS) {
            assertThrows(IllegalArgumentException.class, () -> resolve("sub\\..\\..\\outside"));
            assertEquals(List.of("sub", "deeper", "x.txt"), resolve("sub\\deeper/x.txt").components());
        } else {
            // a backslash is an ordinary file name character here: one component, still inside
            assertEquals(1, resolve("a\\..\\..\\outside").components().size());
        }
    }

    @Test
    void repeatedSeparators() {
        assertEquals(List.of("sub", "x"), resolve("sub///x").components());
        assertEquals(List.of("sub", "x"), resolve("sub/./././x").components());
    }

    @Test
    void windowsOnlyForms() {
        assumeTrue(PathResolver.WINDOWS, "Windows path syntax");
        for (String p : new String[]{"C:\\Windows\\win.ini", "C:win.ini", "\\Windows\\win.ini", "/Windows/win.ini",
                "\\\\server\\share\\x", "//server/share/x", "\\\\?\\C:\\Windows", "\\\\.\\C:\\Windows",
                "\\\\localhost\\C$\\Windows", "sub\\..\\..\\..\\Windows"}) {
            assertRefusedOrConfined(p);
        }
        assertThrows(IllegalArgumentException.class, () -> resolve("\\\\server\\share\\x"));
        assertThrows(IllegalArgumentException.class, () -> resolve("\\\\?\\" + outside));
    }

    @Test
    void windowsCaseTricks() {
        assumeTrue(PathResolver.WINDOWS, "case-insensitive file system");
        String upperRoot = root.toString().toUpperCase();
        // the same directory spelled in another case is still the root: inside, not an escape
        assertDoesNotThrow(() -> resolve(upperRoot));
        assertRefusedOrConfined("SUB\\DEEPER\\..\\..\\..\\OUTSIDE");
        assertRefusedOrConfined("Sub/../../Outside/SECRET.TXT");
        JailedPath jp = resolve("SUB/Deeper/x.TXT");
        assertTrue(PathResolver.realPath(jp).startsWith(jp.realRoot()));
    }

    @Test
    void trailingDotsAndSpaces() {
        for (String p : new String[]{"sub.", "sub ", "sub. .", "sub/.. ", "sub/.. /..", "sub/.../..", "... /../x", "..../x",
                ". ", ".. ", "sub./../..", "sub /../.."}) {
            assertRefusedOrConfined(p);
        }
        if (PathResolver.WINDOWS) {
            // Win32 strips trailing dots and spaces: "sub. " is "sub"; never an escape
            assertRefusedOrConfined("sub. /deeper");
            assertRefusedOrConfined(".. /outside");
        }
    }

    @Test
    void invalidNamesAreRefusedNotEscaped() {
        assertRefusedOrConfined("sub/\0/x");
        assertRefusedOrConfined("sub/x\0../../..");
        if (PathResolver.WINDOWS) {
            for (String p : new String[]{"CON", "NUL", "sub/aux.txt", "a:b", "sub/x:stream", "a*b", "a?b", "a<b", "a|b"}) {
                assertRefusedOrConfined(p);
            }
        }
    }

    @Test
    void longNames() {
        String long255 = "n".repeat(255);
        assertEquals(List.of(long255), resolve(long255).components());
        assertRefusedOrConfined("n".repeat(300));
        assertRefusedOrConfined("sub/" + "d/".repeat(400) + "x");
        assertRefusedOrConfined("sub/" + "../".repeat(400) + "x");
        assertThrows(IllegalArgumentException.class, () -> resolve("../".repeat(500) + "outside"));
        assertRefusedOrConfined("x".repeat(70_000));
    }

    @Test
    void componentsAreNeverMutableViews() {
        JailedPath jp = resolve("sub/x");
        assertThrows(UnsupportedOperationException.class, () -> jp.components().add("..") );
        assertEquals(List.of("sub"), jp.parent().components());
        assertEquals(List.of("sub", "x", "y"), jp.child("y").components());
        assertNull(resolve("").parent());
    }

    // ── links ────────────────────────────────────────────────────────────────────────────────

    @Test
    void dirLinkEscapeRefusedByDefault() throws IOException {
        assumeTrue(JailTestSupport.linkDir(root.resolve("evil"), outside), "no directory links here");
        assertThrows(IllegalArgumentException.class, () -> resolve("evil"));
        assertThrows(IllegalArgumentException.class, () -> resolve("evil/secret.txt"));
    }

    @Test
    void dirLinkEscapeModes() throws IOException {
        assumeTrue(JailTestSupport.linkDir(root.resolve("evil"), outside), "no directory links here");
        assertDoesNotThrow(() -> PathResolver.resolve(root, "evil/secret.txt", ExternalLinks.READ, false));
        assertThrows(IllegalArgumentException.class,
                () -> PathResolver.resolve(root, "evil/secret.txt", ExternalLinks.READ, true));
        assertDoesNotThrow(() -> PathResolver.resolve(root, "evil/secret.txt", ExternalLinks.READ_WRITE, true));
    }

    @Test
    void notYetExistingTargetUnderLinkedDirIsJudgedByTheLink() throws IOException {
        assumeTrue(JailTestSupport.linkDir(root.resolve("evil"), outside), "no directory links here");
        assertThrows(IllegalArgumentException.class, () -> resolve("evil/new/deep/file.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> PathResolver.resolve(root, "evil/new/deep/file.txt", ExternalLinks.READ, true));
        JailedPath jp = PathResolver.resolve(root, "evil/new/file.txt", ExternalLinks.READ_WRITE, true);
        assertNull(PathResolver.realComponents(jp), "classified as leaving the root");
    }

    @Test
    void linkInsideIsFollowedAndClassifiedInside() throws IOException {
        assumeTrue(JailTestSupport.linkDir(root.resolve("alias"), root.resolve("sub")), "no directory links here");
        JailedPath jp = resolve("alias/deeper/new.txt");
        assertEquals(List.of("sub", "deeper", "new.txt"), PathResolver.realComponents(jp));
        assertTrue(PathResolver.realPath(jp).startsWith(jp.realRoot()));
    }

    @Test
    void realComponentsOfPlainAndMissingPaths() {
        assertEquals(List.of("sub", "deeper", "a", "b"), PathResolver.realComponents(resolve("sub/deeper/a/b")));
        assertEquals(List.of(), PathResolver.realComponents(resolve("")));
    }

    @Test
    void followLinkClassifiesTargets() throws IOException {
        Path inside = root.resolve("sub");
        Path link = root.resolve("l-in");
        assumeTrue(JailTestSupport.linkDir(link, inside), "no directory links here");
        JailedPath jp = resolve("l-in/deeper");
        PathResolver.Hop hop = PathResolver.followLink(link, jp.components(), 0, jp, false, 0, "l-in/deeper");
        assertNotNull(hop);
        assertEquals(List.of("sub", "deeper"), hop.components());
        assertEquals(jp.realRoot(), hop.base());

        Path evil = root.resolve("l-out");
        assumeTrue(JailTestSupport.linkDir(evil, outside), "no directory links here");
        JailedPath ext = PathResolver.resolve(root, "sub", ExternalLinks.NONE, false);
        assertThrows(IllegalArgumentException.class,
                () -> PathResolver.followLink(evil, List.of("l-out", "secret.txt"), 0, ext, false, 0, "l-out/secret.txt"));
        JailedPath extRead = new JailedPath(ext.root(), ext.realRoot(), List.of("l-out", "secret.txt"), ext.lexical(),
                ExternalLinks.READ);
        PathResolver.Hop out = PathResolver.followLink(evil, extRead.components(), 0, extRead, false, 0, "x");
        assertEquals(outside.toRealPath().getRoot(), out.base());
        assertEquals("secret.txt", out.components().get(out.components().size() - 1));
        assertThrows(IllegalArgumentException.class,
                () -> PathResolver.followLink(evil, extRead.components(), 0, extRead, true, 0, "x"));
    }

    @Test
    void symlinkLoopIsBoundedByHopLimit() throws IOException {
        Path a = root.resolve("loop-a");
        Path b = root.resolve("loop-b");
        assumeTrue(JailTestSupport.linkFile(a, b) && JailTestSupport.linkFile(b, a), "no symlinks here");
        // the early check cannot resolve a loop: refused at resolve, or classified without hanging
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            try {
                JailedPath jp = resolve("loop-a/x");
                assertNull(PathResolver.realComponents(jp));
            } catch (IllegalArgumentException expected) {
                // refused
            }
        });
        JailedPath jp = resolve("loop-a");
        JailedPath l = jp;
        assertThrows(IllegalArgumentException.class, () -> PathResolver.followLink(a, l.components(), 0, l, false,
                PathResolver.MAX_LINK_HOPS, "loop-a"));
    }

    @Test
    void danglingLinkTargetIsOnlyClassified() throws IOException {
        Path link = root.resolve("dangling");
        assumeTrue(JailTestSupport.linkDir(link, root.resolve("nowhere")) || JailTestSupport.linkFile(link, root.resolve("nowhere")),
                "no links here");
        JailedPath jp = resolve("dangling");
        assertDoesNotThrow(() -> PathResolver.followLink(link, jp.components(), 0, jp, false, 0, "dangling"));
    }

    @Test
    void selfReferencingEntryIsNotALink() throws IOException {
        Path plain = Files.writeString(root.resolve("plain.txt"), "x");
        // linkTarget of a regular file resolves to itself (the reparse-point rule): no hop
        JailedPath jp = resolve("plain.txt");
        assertNull(PathResolver.followLink(plain.toRealPath(), jp.components(), 0, jp, false, 0, "plain.txt"));
    }

    @Test
    void symlinkToFileOutsideRefused() throws IOException {
        assumeTrue(JailTestSupport.linkFile(root.resolve("s.txt"), outside.resolve("secret.txt")), "no file symlinks here");
        // a file link is only caught when the walk follows it; the early check judges the existing entry itself
        JailedPath jp = resolve("s.txt");
        assertNotNull(jp);
        assertThrows(IllegalArgumentException.class, () -> PathResolver.followLink(root.toRealPath().resolve("s.txt"),
                jp.components(), 0, jp, false, 0, "s.txt"));
    }

    @Test
    void isLinkRecognizesLinksNotFiles() throws IOException {
        Path f = Files.writeString(root.resolve("f.txt"), "x");
        assertFalse(PathResolver.isLink(Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)));
        assertFalse(PathResolver.isLink(Files.readAttributes(root.resolve("sub"),
                java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS)));
        Path l = root.resolve("l");
        assumeTrue(JailTestSupport.linkDir(l, root.resolve("sub")), "no directory links here");
        assertTrue(PathResolver.isLink(Files.readAttributes(l, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)));
    }

    // ── hard links ───────────────────────────────────────────────────────────────────────────

    @Test
    void ownHardLinkAndMissingFileAreNotRefused() throws IOException {
        Path f = Files.writeString(root.resolve("f.txt"), "x");
        try {
            Files.createLink(root.resolve("h.txt"), f);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "no hard links here");
        }
        assertDoesNotThrow(() -> PathResolver.refuseForeignHardLink(root.resolve("h.txt"), "h.txt"));
        assertDoesNotThrow(() -> PathResolver.refuseForeignHardLink(root.resolve("missing.txt"), "missing.txt"));
    }

    @Test
    void foreignHardLinkIsRefused() throws IOException {
        assumeTrue(!PathResolver.WINDOWS, "the link count is not exposed on Windows");
        // a root-owned file with several links, e.g. /usr/bin/* or /etc/passwd copies, cannot be created without
        // privileges: use an existing multi-link system file when this user does not own it
        Path candidate = null;
        for (String c : new String[]{"/usr/bin/perl", "/bin/sh", "/usr/bin/vi", "/usr/bin/ex", "/usr/bin/view"}) {
            Path p = Path.of(c);
            if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            Object n = Files.getAttribute(p, "unix:nlink", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (n instanceof Integer i && i > 1
                    && !Files.getOwner(p, java.nio.file.LinkOption.NOFOLLOW_LINKS).getName()
                    .equals(System.getProperty("user.name"))) {
                candidate = p;
                break;
            }
        }
        assumeTrue(candidate != null, "no multi-link file owned by another user on this machine");
        Path c = candidate;
        IOException e = assertThrows(IOException.class, () -> PathResolver.refuseForeignHardLink(c, "shown"));
        assertTrue(e.getMessage().contains("shown"));
    }
}

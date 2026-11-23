package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.kendar.llm.harness.HarnessTestSupport.json;

/**
 * {@code files.externalLinks}: a link inside the work dir whose target is outside it. Links inside are always followed;
 * outside they depend on the mode. Directory links are junctions on Windows without the symlink privilege; file links
 * need the privilege and their tests skip without it.
 */
class ExternalLinksTest {

    @TempDir
    Path tmp;

    Path root;
    Path outside;
    FileSystemAgentTools fs = new FileSystemAgentTools();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("work"));
        outside = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(outside.resolve("secret.txt"), "SECRET outside\n");
        Files.createDirectories(outside.resolve("sub"));
        Files.writeString(outside.resolve("sub/inner.txt"), "SECRET inner\n");
        Files.createDirectories(root.resolve("real"));
        Files.writeString(root.resolve("real/f.txt"), "SECRET inside\n");
        assumeTrue(JailTestSupport.canLinkDirs(tmp), "links are not permitted on this machine");
        assertTrue(JailTestSupport.linkDir(root.resolve("ext"), outside));
        assertTrue(JailTestSupport.linkDir(root.resolve("in"), root.resolve("real")));
    }

    @AfterEach
    void reset() {
        JailTestSupport.reset();
    }

    static Stream<Arguments> strategiesAndModes() {
        List<Arguments> out = new ArrayList<>();
        JailTestSupport.strategies().forEach(s -> {
            for (ExternalLinks m : ExternalLinks.values()) out.add(Arguments.of(s, m));
        });
        return out.stream();
    }

    private ToolContext ctx(ExternalLinks mode) {
        return new ToolContext("c1", root).withLinks(mode);
    }

    private String run(ExternalLinks mode, String tool, String args) throws Exception {
        return fs.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow()
                .execute(ctx(mode), json(args));
    }

    // ── the enum and the context ─────────────────────────────────────────────────────────────

    @Test
    void parsesTheSettingValues() {
        assertEquals(ExternalLinks.NONE, ExternalLinks.parse("none", ExternalLinks.READ));
        assertEquals(ExternalLinks.READ, ExternalLinks.parse(" READ ", ExternalLinks.NONE));
        assertEquals(ExternalLinks.READ_WRITE, ExternalLinks.parse("readwrite", ExternalLinks.NONE));
        assertEquals(ExternalLinks.READ, ExternalLinks.parse(null, ExternalLinks.READ));
        assertEquals(ExternalLinks.READ, ExternalLinks.parse(" ", ExternalLinks.READ));
        assertThrows(IllegalArgumentException.class, () -> ExternalLinks.parse("write", ExternalLinks.READ));
        assertEquals("readwrite", ExternalLinks.READ_WRITE.settingValue());
    }

    @Test
    void aToolContextWithoutAPolicyRefusesExternalLinks() {
        assertEquals(ExternalLinks.NONE, new ToolContext("c", root).links());
        assertEquals(ExternalLinks.READ, new ToolContext("c", root).withLinks(ExternalLinks.READ).links());
        assertEquals(ExternalLinks.READ, new ToolContext("c", root).withLinks(ExternalLinks.READ).withToolUseId("t").links());
    }

    // ── a link that stays inside is followed in every mode ───────────────────────────────────

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("strategiesAndModes")
    void aLinkInsideToInsideWorksInEveryMode(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;

        assertTrue(run(mode, "read_file", "{'path':'in/f.txt'}").contains("SECRET inside"));
        run(mode, "write_file", "{'path':'in/g.txt','content':'made'}");
        run(mode, "edit_file", "{'path':'in/f.txt','old_string':'inside','new_string':'edited'}");

        assertEquals("made", Files.readString(root.resolve("real/g.txt")));
        assertEquals("SECRET edited\n", Files.readString(root.resolve("real/f.txt")));
    }

    // ── a link that leaves the work dir, per mode ────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("strategiesAndModes")
    void aLinkOutsideIsFollowedPerMode(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;

        // reads
        if (mode == ExternalLinks.NONE) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> run(mode, "read_file", "{'path':'ext/secret.txt'}"));
            assertTrue(e.getMessage().contains("is a link that leaves the work dir"), e.getMessage());
        } else {
            assertTrue(run(mode, "read_file", "{'path':'ext/secret.txt'}").contains("SECRET outside"));
            assertTrue(run(mode, "ls", "{'path':'ext'}").contains("secret.txt"));
        }

        // writes
        for (String call : new String[]{
                "edit_file|{'path':'ext/secret.txt','old_string':'outside','new_string':'edited'}",
                "write_file|{'path':'ext/secret.txt','content':'pwn'}",
                "write_file|{'path':'ext/new.txt','content':'pwn'}",
                "write_file|{'path':'ext/deep/er/new.txt','content':'pwn'}",
                "write_file|{'path':'ext/secret.txt','content':'pwn','append':true}"}) {
            String[] c = call.split("\\|", 2);
            if (mode == ExternalLinks.READ_WRITE) {
                run(mode, c[0], c[1]);
            } else {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(mode, c[0], c[1]), call);
                String want = mode == ExternalLinks.NONE ? "is a link that leaves the work dir" : "files.externalLinks";
                assertTrue(e.getMessage().contains(want), call + ": " + e.getMessage());
            }
        }
        if (mode == ExternalLinks.READ_WRITE) {
            assertTrue(Files.readString(outside.resolve("secret.txt")).contains("pwn"));
            assertTrue(Files.exists(outside.resolve("new.txt")));
            assertTrue(Files.exists(outside.resolve("deep/er/new.txt")));
        } else {
            assertEquals("SECRET outside\n", Files.readString(outside.resolve("secret.txt")));
            assertEquals(List.of("secret.txt", "sub/inner.txt"), JailTestSupport.files(outside));
        }
    }

    @ParameterizedTest
    @EnumSource(ExternalLinks.class)
    void aFileLinkOutsideIsFollowedPerMode(ExternalLinks mode) throws Exception {
        assumeTrue(JailTestSupport.canLinkFiles(tmp), "file links are not permitted on this machine");
        assertTrue(JailTestSupport.linkFile(root.resolve("flink"), outside.resolve("secret.txt")));

        if (mode == ExternalLinks.NONE) {
            assertThrows(IllegalArgumentException.class, () -> run(mode, "read_file", "{'path':'flink'}"));
        } else {
            assertTrue(run(mode, "read_file", "{'path':'flink'}").contains("SECRET outside"));
        }
        if (mode == ExternalLinks.READ_WRITE) {
            run(mode, "edit_file", "{'path':'flink','old_string':'outside','new_string':'changed'}");
            assertEquals("SECRET changed\n", Files.readString(outside.resolve("secret.txt")));
            assertTrue(Files.isSymbolicLink(root.resolve("flink")), "the link itself is kept");
        } else {
            assertThrows(IllegalArgumentException.class,
                    () -> run(mode, "write_file", "{'path':'flink','content':'pwn'}"));
            assertEquals("SECRET outside\n", Files.readString(outside.resolve("secret.txt")));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategiesAndModes")
    void aLexicalEscapeIsRefusedInEveryMode(PathJail.Strategy s, ExternalLinks mode) {
        PathJail.forced = s;
        for (String p : new String[]{"../elsewhere/secret.txt", tmp.resolve("elsewhere/secret.txt").toString()}) {
            assertThrows(IllegalArgumentException.class, () -> run(mode, "read_file", "{'path':'" + p.replace("\\", "/") + "'}"), p);
        }
    }

    // ── chained and dangling links ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("strategiesAndModes")
    void aChainOfLinksEndingOutsideFollowsTheModeOfItsLastHop(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;
        assertTrue(JailTestSupport.linkDir(root.resolve("chain"), root.resolve("ext"))); // inside -> a link out
        assertTrue(JailTestSupport.linkDir(root.resolve("chain2"), root.resolve("chain")));

        if (mode == ExternalLinks.NONE) {
            assertThrows(IllegalArgumentException.class, () -> run(mode, "read_file", "{'path':'chain2/secret.txt'}"));
        } else {
            assertTrue(run(mode, "read_file", "{'path':'chain2/secret.txt'}").contains("SECRET outside"));
            assertTrue(run(mode, "read_file", "{'path':'chain/sub/inner.txt'}").contains("SECRET inner"));
        }
        if (mode != ExternalLinks.READ_WRITE) {
            assertThrows(IllegalArgumentException.class,
                    () -> run(mode, "write_file", "{'path':'chain2/x.txt','content':'pwn'}"));
            assertFalse(Files.exists(outside.resolve("x.txt")));
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("strategiesAndModes")
    void aLinkOutsideThatPointsBackInsideIsFollowedAsInside(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;
        assertTrue(JailTestSupport.linkDir(outside.resolve("back"), root.resolve("real")));
        if (mode == ExternalLinks.NONE) return; // the first hop already refuses
        assertTrue(run(mode, "read_file", "{'path':'ext/back/f.txt'}").contains("SECRET inside"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("strategiesAndModes")
    void aDanglingLinkIsRefused(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;
        // a directory link to something that is not there: a junction cannot be made dangling, a symlink can
        boolean made;
        try {
            Files.createSymbolicLink(root.resolve("gone"), tmp.resolve("not-there"));
            made = true;
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
            made = false;
        }
        assumeTrue(made, "symlinks are not permitted on this machine");

        assertThrows(IllegalArgumentException.class, () -> run(mode, "read_file", "{'path':'gone/x.txt'}"));
        assertThrows(IllegalArgumentException.class, () -> run(mode, "read_file", "{'path':'gone'}"));
        assertThrows(IllegalArgumentException.class,
                () -> run(mode, "write_file", "{'path':'gone/x.txt','content':'pwn'}"));
        assertFalse(Files.exists(tmp.resolve("not-there")));
    }

    // ── walkers ──────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("strategiesAndModes")
    void walkersDescendIntoAnExternalDirectoryLinkOnlyInReadAndReadWrite(PathJail.Strategy s, ExternalLinks mode)
            throws Exception {
        PathJail.forced = s;
        boolean descends = mode != ExternalLinks.NONE;

        String grep = run(mode, "grep", "{'pattern':'SECRET'}");
        String glob = run(mode, "glob", "{'pattern':'**/*.txt'}");
        String code = run(mode, "grep_code", "{'pattern':'SECRET'}");

        assertTrue(grep.contains("real/f.txt:1:SECRET inside"), grep);
        assertEquals(descends, grep.contains("ext/secret.txt:1:SECRET outside"), grep);
        assertEquals(descends, grep.contains("ext/sub/inner.txt:1:SECRET inner"), grep);
        assertEquals(descends, glob.contains("ext/sub/inner.txt"), glob);
        assertEquals(descends, code.contains("ext/secret.txt"), code);
        // the link to a directory inside is not entered: its files are listed where they lie
        assertFalse(grep.contains("in/f.txt"), grep);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategiesAndModes")
    void aWalkThroughLoopingLinksEnds(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;
        assumeTrue(mode == ExternalLinks.READ);
        assertTrue(JailTestSupport.linkDir(outside.resolve("self"), outside));      // a link to its own directory
        assertTrue(JailTestSupport.linkDir(outside.resolve("up"), root));           // a link back to the work dir
        assertTrue(JailTestSupport.linkDir(outside.resolve("sub/up2"), outside));   // a link to an ancestor

        String grep = run(mode, "grep", "{'pattern':'SECRET'}");

        assertTrue(grep.contains("ext/secret.txt:1:SECRET outside"), grep);
        long inner = grep.lines().filter(l -> l.endsWith("SECRET inner")).count();
        assertTrue(inner >= 1 && inner <= 3, "bounded: " + grep);
    }

    // ── deletes never follow a link ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategiesAndModes")
    void aRecursiveDeleteRemovesTheLinkAndKeepsTheTarget(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;
        assumeTrue(mode == ExternalLinks.READ_WRITE);
        Files.createDirectories(root.resolve("tree/keep"));
        Files.writeString(root.resolve("tree/keep/x.txt"), "x");
        assertTrue(JailTestSupport.linkDir(root.resolve("tree/keep/out"), outside));

        PathJail.deleteTree(PathResolver.resolve(root, "tree", mode, true));

        assertFalse(Files.exists(root.resolve("tree"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of("secret.txt", "sub/inner.txt"), JailTestSupport.files(outside));
        // and a link itself is deleted, not what it points to
        PathJail.deleteTree(PathResolver.resolve(root, "ext", mode, true));
        assertFalse(Files.exists(root.resolve("ext"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of("secret.txt", "sub/inner.txt"), JailTestSupport.files(outside));
    }

    // ── atomic writer, checkpoints, approval preview ─────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategiesAndModes")
    void theAtomicWriterFollowsAFileLinkOutsideOnlyInReadWrite(PathJail.Strategy s, ExternalLinks mode) throws Exception {
        PathJail.forced = s;
        assumeTrue(JailTestSupport.canLinkFiles(tmp), "file links are not permitted on this machine");
        assertTrue(JailTestSupport.linkFile(root.resolve("flink"), outside.resolve("secret.txt")));

        if (mode == ExternalLinks.READ_WRITE) {
            AtomicFiles.write(PathResolver.resolve(root, "flink", mode, true), new byte[]{'o', 'k'});
            assertEquals("ok", Files.readString(outside.resolve("secret.txt")));
            assertTrue(Files.isSymbolicLink(root.resolve("flink")));
        } else {
            assertThrows(IllegalArgumentException.class,
                    () -> AtomicFiles.write(PathResolver.resolve(root, "flink", mode, true), new byte[]{'o', 'k'}));
            assertEquals("SECRET outside\n", Files.readString(outside.resolve("secret.txt")));
        }
    }

    @Test
    void undoRestoresAThroughLinkEditOnlyInReadWrite() throws Exception {
        Checkpoints cp = new Checkpoints();
        FileSystemAgentTools tools = new FileSystemAgentTools(null, cp);
        ToolContext rw = ctx(ExternalLinks.READ_WRITE);
        cp.beginTurn("c1", root);
        tools.tools().stream().filter(t -> t.name().equals("write_file")).findFirst().orElseThrow()
                .execute(rw, json("{'path':'ext/secret.txt','content':'changed'}"));
        assertEquals("changed", Files.readString(outside.resolve("secret.txt")));

        // a policy that no longer allows writes through the link leaves the file as it is, and says why
        Checkpoints.UndoResult refused = cp.undo("c1", root, 1, ExternalLinks.READ);
        assertEquals("changed", Files.readString(outside.resolve("secret.txt")));
        assertEquals(1, refused.refused().size(), refused.message());
        assertTrue(refused.refused().get(0).contains("files.externalLinks is read"), refused.refused().get(0));
        assertTrue(refused.message().contains("not restored"), refused.message());

        cp.beginTurn("c1", root);
        tools.tools().stream().filter(t -> t.name().equals("write_file")).findFirst().orElseThrow()
                .execute(rw, json("{'path':'ext/secret.txt','content':'changed again'}"));
        Checkpoints.UndoResult done = cp.undo("c1", root, 1, ExternalLinks.READ_WRITE);
        assertEquals(List.of("ext/secret.txt"), done.restored(), done.message());
        assertEquals("changed", Files.readString(outside.resolve("secret.txt")));
    }

    @Test
    void theApprovalPreviewShowsTheResolvedTargetNotTheLink() throws Exception {
        HarnessTool write = fs.tools().stream().filter(t -> t.name().equals("write_file")).findFirst().orElseThrow();
        HarnessTool edit = fs.tools().stream().filter(t -> t.name().equals("edit_file")).findFirst().orElseThrow();
        Path real = outside.resolve("secret.txt").toRealPath();
        ToolContext rw = ctx(ExternalLinks.READ_WRITE);

        String w = write.preview(rw, json("{'path':'ext/secret.txt','content':'x'}"));
        String e = edit.preview(rw, json("{'path':'ext/secret.txt','old_string':'a','new_string':'b'}"));

        assertTrue(w.startsWith("--- ext/secret.txt -> " + real), w);
        assertTrue(e.startsWith("--- ext/secret.txt -> " + real), e);
        assertTrue(w.contains("leaves the work dir"), w);
        // a path inside shows no target
        assertTrue(write.preview(rw, json("{'path':'in/f.txt','content':'x'}")).startsWith("--- in/f.txt\n"));
    }

    @Test
    void scriptsAreConfinedToTheirScratchDirWhateverThePolicy() throws Exception {
        // the jail of run_js replaces the context root and forbids every link out of it
        ToolContext jailed = ctx(ExternalLinks.READ_WRITE).withRoot(root).withLinks(ExternalLinks.NONE);
        assertThrows(IllegalArgumentException.class,
                () -> fs.tools().stream().filter(t -> t.name().equals("read_file")).findFirst().orElseThrow()
                        .execute(jailed, json("{'path':'ext/secret.txt'}")));
    }
}

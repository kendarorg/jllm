package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.kendar.llm.harness.HarnessTestSupport.json;

/**
 * The race the jail closes: the path was checked, then a directory or the file is swapped for a link to a place outside
 * the work dir, right before the open. The seam {@link PathJail#beforeOpen} makes the swap deterministic. In every case
 * the secret outside never shows up and nothing is created outside; the call itself may succeed (on the original
 * file) or fail. Each test runs under every strategy the machine supports.
 */
class PathJailRaceTest {

    @TempDir
    Path tmp;

    Path root;
    Path outside;
    ToolContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("work"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        ctx = new ToolContext("c1", root);
    }

    @AfterEach
    void tearDown() {
        JailTestSupport.reset();
    }

    static java.util.stream.Stream<PathJail.Strategy> strategies() {
        return JailTestSupport.strategies();
    }

    private void use(PathJail.Strategy s) throws IOException {
        PathJail.forced = s;
        assumeTrue(JailTestSupport.canLinkDirs(tmp), "links are not permitted on this machine");
    }

    private String run(FileSystemAgentTools fs, String tool, String input) throws Exception {
        return fs.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow()
                .execute(ctx, json(input));
    }

    private String attempt(FileSystemAgentTools fs, String tool, String input) {
        try {
            return run(fs, tool, input);
        } catch (Exception e) {
            return "refused: " + e.getMessage();
        }
    }

    private String attempt(String tool, String input) {
        return attempt(new FileSystemAgentTools(), tool, input);
    }

    private void assertNothingAppearedOutside(List<String> before) throws IOException {
        assertEquals(before, JailTestSupport.files(outside), "nothing may be created or removed outside");
    }

    // ── reads ─────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedIntermediateDirectoryIsNotRead(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/f.txt"), "INSIDE");
        Files.writeString(outside.resolve("f.txt"), "TOP SECRET");
        JailTestSupport.onNth(1, () -> JailTestSupport.swapDirForLink(root.resolve("sub"), outside));

        String out = attempt("read_file", "{'path':'sub/f.txt'}");

        assertFalse(out.contains("SECRET"), out);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedFinalFileIsNotRead(PathJail.Strategy s) throws Exception {
        use(s);
        assumeTrue(JailTestSupport.canLinkFiles(tmp), "file links are not permitted on this machine");
        Files.writeString(root.resolve("f.txt"), "INSIDE");
        Files.writeString(outside.resolve("secret.txt"), "TOP SECRET");
        JailTestSupport.onNth(1, () -> JailTestSupport.swapFileForLink(root.resolve("f.txt"),
                outside.resolve("secret.txt")));

        String out = attempt("read_file", "{'path':'f.txt'}");

        assertFalse(out.contains("SECRET"), out);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedDirectoryDuringAGrepWalkIsNotSearched(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("a.txt"), "needle one");
        Files.writeString(root.resolve("d/b.txt"), "needle two");
        Files.writeString(outside.resolve("b.txt"), "needle TOP SECRET");
        // the second open is the file in d, after the walk listed it
        JailTestSupport.onNth(2, () -> JailTestSupport.swapDirForLink(root.resolve("d"), outside));

        String out = attempt("grep", "{'pattern':'needle'}");

        assertTrue(out.contains("a.txt:1:needle one"), out);
        assertFalse(out.contains("SECRET"), out);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedDirectoryDuringAGrepCodeWalkIsNotRead(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("a.txt"), "void one() {\n");
        Files.writeString(root.resolve("d/b.txt"), "void two() {\n");
        Files.writeString(outside.resolve("b.txt"), "void SECRET() {\n");
        JailTestSupport.onNth(2, () -> JailTestSupport.swapDirForLink(root.resolve("d"), outside));

        String out = attempt("grep_code", "{}");

        assertTrue(out.contains("one()"), out);
        assertFalse(out.contains("SECRET"), out);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedDirectoryDuringALsIsNotListed(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("d/b.txt"), "x");
        Files.writeString(outside.resolve("TOPSECRET.txt"), "x");
        JailTestSupport.onNth(1, () -> JailTestSupport.swapDirForLink(root.resolve("d"), outside));

        String out = attempt("ls", "{'path':'d'}");

        assertFalse(out.contains("TOPSECRET"), out);
    }

    // ── writes ────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedDirectoryIsNotWrittenInto(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/keep.txt"), "x");
        Files.writeString(outside.resolve("keep.txt"), "TOP SECRET");
        List<String> before = JailTestSupport.files(outside);
        JailTestSupport.onNth(1, () -> JailTestSupport.swapDirForLink(root.resolve("sub"), outside));

        attempt("write_file", "{'path':'sub/new.txt','content':'pwn'}");

        assertNothingAppearedOutside(before);
        assertEquals("TOP SECRET", Files.readString(outside.resolve("keep.txt")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedDirectoryIsNotAppendedTo(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/log.txt"), "x\n");
        Files.writeString(outside.resolve("log.txt"), "TOP SECRET\n");
        List<String> before = JailTestSupport.files(outside);
        JailTestSupport.onNth(1, () -> JailTestSupport.swapDirForLink(root.resolve("sub"), outside));

        attempt("write_file", "{'path':'sub/log.txt','content':'pwn','append':true}");

        assertNothingAppearedOutside(before);
        assertEquals("TOP SECRET\n", Files.readString(outside.resolve("log.txt")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedDirectoryIsNotEdited(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/e.txt"), "hello\n");
        Files.writeString(outside.resolve("e.txt"), "TOP SECRET hello\n");
        List<String> before = JailTestSupport.files(outside);
        JailTestSupport.onNth(1, () -> JailTestSupport.swapDirForLink(root.resolve("sub"), outside));

        String out = attempt("edit_file", "{'path':'sub/e.txt','old_string':'hello','new_string':'bye'}");

        assertNothingAppearedOutside(before);
        assertEquals("TOP SECRET hello\n", Files.readString(outside.resolve("e.txt")));
        assertFalse(out.contains("SECRET"), out);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void swappedFinalFileIsNotWrittenThrough(PathJail.Strategy s) throws Exception {
        use(s);
        assumeTrue(JailTestSupport.canLinkFiles(tmp), "file links are not permitted on this machine");
        Files.writeString(root.resolve("w.txt"), "old");
        Files.writeString(outside.resolve("secret.txt"), "TOP SECRET");
        JailTestSupport.onNth(1, () -> JailTestSupport.swapFileForLink(root.resolve("w.txt"),
                outside.resolve("secret.txt")));

        attempt("write_file", "{'path':'w.txt','content':'pwn','append':true}");

        assertEquals("TOP SECRET", Files.readString(outside.resolve("secret.txt")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void atomicReplaceNeverLeavesTheDirectory(PathJail.Strategy s) throws Exception {
        use(s);
        for (int stage = 1; stage <= 2; stage++) { // the temp file create, then the rename over the target
            String d = "sub" + stage;
            Files.createDirectories(root.resolve(d));
            Files.writeString(root.resolve(d + "/w.txt"), "old");
            Files.writeString(outside.resolve("w.txt"), "TOP SECRET");
            List<String> before = JailTestSupport.files(outside);
            JailTestSupport.onNth(stage, () -> JailTestSupport.swapDirForLink(root.resolve(d), outside));

            attempt("write_file", "{'path':'" + d + "/w.txt','content':'pwn'}");

            assertNothingAppearedOutside(before);
            assertEquals("TOP SECRET", Files.readString(outside.resolve("w.txt")), "stage " + stage);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void checkpointRecordNeverLeavesTheWorkDir(PathJail.Strategy s) throws Exception {
        use(s);
        Files.writeString(root.resolve("f.txt"), "old");
        Files.writeString(outside.resolve("TOPSECRET.txt"), "TOP SECRET");
        Files.createDirectories(root.resolve(".jllm/checkpoints/c1"));
        Checkpoints cp = new Checkpoints();
        cp.beginTurn("c1", root);
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);
        List<String> before = JailTestSupport.files(outside);
        JailTestSupport.onNth(1, () -> JailTestSupport.swapDirForLink(root.resolve(".jllm"), outside));

        attempt(fs, "write_file", "{'path':'f.txt','content':'new'}");

        assertNothingAppearedOutside(before);
        assertEquals("TOP SECRET", Files.readString(outside.resolve("TOPSECRET.txt")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void undoNeverRestoresThroughASwappedDirectory(PathJail.Strategy s) throws Exception {
        use(s);
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/f.txt"), "old");
        Checkpoints cp = new Checkpoints();
        cp.beginTurn("c1", root);
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);
        run(fs, "write_file", "{'path':'sub/f.txt','content':'new'}");
        List<String> before = JailTestSupport.files(outside);
        JailTestSupport.onNth(1, () -> JailTestSupport.swapDirForLink(root.resolve("sub"), outside));

        try {
            cp.undo("c1", root, 1, ExternalLinks.NONE);
        } catch (RuntimeException e) {
            // refused: fine
        }

        assertNothingAppearedOutside(before);
    }
}

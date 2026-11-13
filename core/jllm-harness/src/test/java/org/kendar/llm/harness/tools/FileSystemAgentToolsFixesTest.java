package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.kendar.llm.harness.HarnessTestSupport.json;

/** Symlinks in searches, charsets, line endings, read/edit limits, atomic writes and parallel edits. */
class FileSystemAgentToolsFixesTest {

    @TempDir
    Path tmp;

    Path root;
    Path outside;
    ToolContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("work"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "TOP SECRET\n");
        ctx = new ToolContext("c1", root);
    }

    private static String run(FileSystemAgentTools fs, ToolContext ctx, String tool, String input) throws Exception {
        return fs.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow().execute(ctx, json(input));
    }

    private String run(String tool, String input) throws Exception {
        return run(new FileSystemAgentTools(), ctx, tool, input);
    }

    private boolean symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception | Error e) {
            return false;
        }
    }

    // ── symlinks in grep / grep_code / glob / ls ─────────────────────────────────────────────

    @Test
    void searchesSkipFilesLinkedFromOutside() throws Exception {
        assumeTrue(symlink(root.resolve("leak.txt"), outside.resolve("secret.txt")), "symlinks not permitted");
        Files.writeString(root.resolve("own.txt"), "SECRET of my own {\n");

        String grep = run("grep", "{'pattern':'SECRET'}");
        assertTrue(grep.contains("own.txt"), grep);
        assertFalse(grep.contains("leak.txt"), grep);
        assertFalse(run("grep_code", "{'pattern':'SECRET'}").contains("leak.txt"));
        assertFalse(run("glob", "{'pattern':'**/*.txt'}").contains("leak.txt"));
        assertFalse(run("ls", "{}").contains("leak.txt"));
    }

    @Test
    void searchesSkipWindowsJunctionsToOutside() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", root.resolve("jdir").toString(), outside.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0 && Files.exists(root.resolve("jdir/secret.txt")), "cannot create a junction");
        try {
            assertFalse(run("grep", "{'pattern':'SECRET'}").contains("secret.txt"));
            assertFalse(run("grep_code", "{'pattern':'SECRET'}").contains("secret.txt"));
            assertFalse(run("glob", "{'pattern':'**/*.txt'}").contains("secret.txt"));
            assertFalse(run("ls", "{}").contains("jdir"));
        } finally {
            Files.deleteIfExists(root.resolve("jdir")); // removes the junction only
        }
        assertTrue(Files.exists(outside.resolve("secret.txt")));
    }

    @Test
    void linkInsideTheWorkDirIsStillSearched() throws Exception {
        Files.writeString(root.resolve("real.txt"), "needle\n");
        assumeTrue(symlink(root.resolve("alias.txt"), root.resolve("real.txt")), "symlinks not permitted");

        assertTrue(run("grep", "{'pattern':'needle'}").contains("alias.txt"));
    }

    // ── grep glob relative to path ───────────────────────────────────────────────────────────

    @Test
    void grepGlobIsRelativeToPath() throws Exception {
        Files.createDirectories(root.resolve("src/sub"));
        Files.writeString(root.resolve("src/A.java"), "needle\n");
        Files.writeString(root.resolve("src/sub/B.java"), "needle\n");
        Files.writeString(root.resolve("src/c.txt"), "needle\n");

        String out = run("grep", "{'pattern':'needle','path':'src','glob':'*.java'}");
        assertTrue(out.contains("src/A.java:1:needle"), out);
        assertFalse(out.contains("c.txt"), out);
        assertFalse(out.contains("B.java"), out);
        assertTrue(run("grep", "{'pattern':'needle','path':'src','glob':'**/*.java'}").contains("src/sub/B.java"));
        // a glob written from the work dir still works
        assertTrue(run("grep", "{'pattern':'needle','path':'src','glob':'src/*.java'}").contains("src/A.java"));
    }

    // ── edit_file: missing new_string, charset, line endings ────────────────────────────────

    @Test
    void missingNewStringIsAnErrorButEmptyDeletes() throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "keep remove\n");

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> run("edit_file", "{'path':'a.txt','old_string':' remove'}"));
        assertTrue(e.getMessage().contains("new_string"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> run("edit_file", "{'path':'a.txt','edits':[{'old_string':' remove'}]}"));
        assertEquals("keep remove\n", Files.readString(f));

        run("edit_file", "{'path':'a.txt','old_string':' remove','new_string':''}");
        assertEquals("keep\n", Files.readString(f));
    }

    @Test
    void windows1252FileIsWrittenBackInItsCharset() throws Exception {
        Charset cp = Charset.forName("windows-1252");
        Path f = root.resolve("latin.txt");
        Files.write(f, "café = 1\r\nprix = 2 €\r\n".getBytes(cp));

        run("edit_file", "{'path':'latin.txt','old_string':'prix = 2','new_string':'coût = 3'}");

        assertArrayEquals("café = 1\r\ncoût = 3 €\r\n".getBytes(cp), Files.readAllBytes(f));
        assertTrue(run("read_file", "{'path':'latin.txt'}").contains("1\tcafé = 1"));
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> run("edit_file", "{'path':'latin.txt','old_string':'café','new_string':'日本'}"));
        assertTrue(e.getMessage().contains("windows-1252"), e.getMessage());
        assertArrayEquals("café = 1\r\ncoût = 3 €\r\n".getBytes(cp), Files.readAllBytes(f));
    }

    @Test
    void bytesUndefinedInWindows1252RoundTripAsLatin1() throws Exception {
        Path f = root.resolve("odd.txt");
        byte[] orig = {'a', (byte) 0x81, 'b', '\n', 'x', '\n'};
        Files.write(f, orig);

        run("edit_file", "{'path':'odd.txt','old_string':'x','new_string':'y'}");

        assertArrayEquals(new byte[]{'a', (byte) 0x81, 'b', '\n', 'y', '\n'}, Files.readAllBytes(f));
    }

    @Test
    void binaryFileIsRefused() throws Exception {
        Path f = root.resolve("bin.dat");
        byte[] orig = {'a', 0, 'b', '\n'};
        Files.write(f, orig);

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> run("edit_file", "{'path':'bin.dat','old_string':'a','new_string':'c'}"));
        assertTrue(e.getMessage().contains("Binary"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> run("read_file", "{'path':'bin.dat'}"));
        assertArrayEquals(orig, Files.readAllBytes(f));
    }

    @Test
    void mixedLineEndingsArePreservedPerLine() throws Exception {
        Path f = root.resolve("mixed.txt");
        Files.writeString(f, "a\r\nb\nc\r\nd\n");

        run("edit_file", "{'path':'mixed.txt','old_string':'c','new_string':'c1\\nc2'}");
        assertEquals("a\r\nb\nc1\r\nc2\r\nd\n", Files.readString(f));

        run("edit_file", "{'path':'mixed.txt','old_string':'b','new_string':'b1\\nb2'}");
        assertEquals("a\r\nb1\nb2\nc1\r\nc2\r\nd\n", Files.readString(f));
    }

    @Test
    void lastLineWithoutEndingUsesThePreviousLineEnding() throws Exception {
        Path f = root.resolve("tail.txt");
        Files.writeString(f, "a\nb\r\nlast");

        run("edit_file", "{'path':'tail.txt','old_string':'last','new_string':'x\\ny'}");

        assertEquals("a\nb\r\nx\r\ny", Files.readString(f));
    }

    // ── atomic writes ───────────────────────────────────────────────────────────────────────

    @Test
    void writesLeaveNoTempFilesAndKeepLinks() throws Exception {
        Files.writeString(root.resolve("target.txt"), "old\n");
        run("write_file", "{'path':'target.txt','content':'new\\n'}");
        run("edit_file", "{'path':'target.txt','old_string':'new','new_string':'newer'}");
        assertEquals("newer\n", Files.readString(root.resolve("target.txt")));
        try (Stream<Path> s = Files.list(root)) {
            assertEquals(List.of("target.txt"), s.map(p -> p.getFileName().toString()).toList());
        }

        assumeTrue(symlink(root.resolve("link.txt"), root.resolve("target.txt")), "symlinks not permitted");
        run("edit_file", "{'path':'link.txt','old_string':'newer','new_string':'via link'}");
        assertTrue(Files.isSymbolicLink(root.resolve("link.txt")));
        assertEquals("via link\n", Files.readString(root.resolve("target.txt")));
    }

    @Test
    void atomicFilesReplacesAndCreates() throws Exception {
        Path f = root.resolve("x.bin");
        AtomicFiles.write(f, new byte[]{1, 2, 3});
        AtomicFiles.write(f, new byte[]{4});
        assertArrayEquals(new byte[]{4}, Files.readAllBytes(f));
        Path src = root.resolve("src.bin");
        Files.write(src, new byte[]{9, 8});
        AtomicFiles.copy(src, f);
        assertArrayEquals(new byte[]{9, 8}, Files.readAllBytes(f));
        assertThrows(java.io.IOException.class, () -> AtomicFiles.write(root, new byte[]{1}));
    }

    // ── read / edit limits ──────────────────────────────────────────────────────────────────

    @Test
    void largeFileIsReadByLineRangeAndTheSliceIsCapped() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 500; i++) sb.append("line").append(i).append('\n');
        Files.writeString(root.resolve("big.txt"), sb);
        FileSystemAgentTools small = new FileSystemAgentTools(null, null, null, 100); // files.maxReadBytes = 100

        assertEquals("250\tline250\n251\tline251\n",
                run(small, ctx, "read_file", "{'path':'big.txt','offset':250,'limit':2}"));
        String page = run(small, ctx, "read_file", "{'path':'big.txt'}");
        assertTrue(page.length() < 200, page);
        assertTrue(page.contains("of 500; continue with offset="), page);

        Files.writeString(root.resolve("long.txt"), "x".repeat(1000) + "\nshort\n");
        String cut = run(small, ctx, "read_file", "{'path':'long.txt','limit':1}");
        assertTrue(cut.contains("[line cut at 100 characters]"), cut);
        assertFalse(cut.contains("x".repeat(101)), cut);
    }

    @Test
    void editAboveTheReadLimitIsRefused() throws Exception {
        Path f = root.resolve("big.txt");
        Files.writeString(f, "a".repeat(200) + "\nb\n");
        FileSystemAgentTools small = new FileSystemAgentTools(null, null, null, 100);

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> run(small, ctx, "edit_file", "{'path':'big.txt','old_string':'b','new_string':'c'}"));
        assertTrue(e.getMessage().contains("files.maxReadBytes"), e.getMessage());
        assertTrue(e.getMessage().contains("sed"), e.getMessage());
    }

    @Test
    void appendToALargeCrlfFileProbesOnlyTheStart() throws Exception {
        Path f = root.resolve("log.txt");
        String body = "entry\r\n".repeat(20_000); // > 64 KB
        Files.writeString(f, body);

        run("write_file", "{'path':'log.txt','content':'one\\ntwo\\n','append':true}");

        assertEquals(body + "one\r\ntwo\r\n", Files.readString(f));
    }

    @Test
    void appendKeepsTheCharset() throws Exception {
        Charset cp = Charset.forName("windows-1252");
        Path f = root.resolve("l.txt");
        Files.write(f, "café\n".getBytes(cp));

        run("write_file", "{'path':'l.txt','content':'thé\\n','append':true}");

        assertArrayEquals("café\nthé\n".getBytes(cp), Files.readAllBytes(f));
    }

    // ── parallel edits ──────────────────────────────────────────────────────────────────────

    @Test
    void parallelEditsOfOneFileAreAllKept() throws Exception {
        int n = 16;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("value").append(i).append("=old\n");
        Path f = root.resolve("p.txt");
        Files.writeString(f, sb);
        Checkpoints cp = new Checkpoints();
        cp.beginTurn("c1", root);
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                all.add(pool.submit(() -> run(fs, ctx, "edit_file",
                        "{'path':'p.txt','old_string':'value" + k + "=old','new_string':'value" + k + "=new'}")));
            }
            for (Future<String> fu : all) fu.get();
        } finally {
            pool.shutdown();
        }

        String text = Files.readString(f);
        for (int i = 0; i < n; i++) assertTrue(text.contains("value" + i + "=new\n"), text);
        assertEquals(0, PathLocks.size());
        cp.undo("c1", root, 1);
        assertEquals(sb.toString(), Files.readString(f), "the backup is the content before all the edits");
    }

    // ── TextEdits unit level ────────────────────────────────────────────────────────────────

    @Test
    void probeIgnoresACharacterCutAtTheEnd() {
        byte[] full = "aé".getBytes(StandardCharsets.UTF_8);
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 1);

        assertEquals(StandardCharsets.UTF_8, TextEdits.TextFile.probe(cut).charset());
        assertNotEquals(StandardCharsets.UTF_8, TextEdits.TextFile.decode(cut).charset());
    }
}

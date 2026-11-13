package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.config;

/** Spill log cap, background children holding the output pipe, junctions/symlinks, the scratch jail. */
class SandboxLinksAndOutputTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path tmp;

    // ── output ────────────────────────────────────────────────────────────────

    private static byte[] lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append("line-").append(i).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void spillLogIsCappedAndMarked() throws Exception {
        TailCapture c = new TailCapture(1024, 4096).read(new ByteArrayInputStream(lines(20_000)));
        assertTrue(c.truncated());
        assertTrue(c.logTruncated());
        assertTrue(c.text().endsWith("line-20000\n"));
        assertTrue(c.omittedLines() > 0);
        byte[] log = Files.readAllBytes(c.log());
        assertTrue(new String(log, StandardCharsets.UTF_8).startsWith("line-1\n"));
        assertTrue(new String(log, StandardCharsets.UTF_8).endsWith("[log truncated at 4096 bytes]\n"));
        assertTrue(log.length <= 4096 + 40, "log size " + log.length);
        Files.delete(c.log());
    }

    @Test
    void spillLogUnlimitedWhenTheCapIsZero() throws Exception {
        byte[] all = lines(20_000);
        TailCapture c = new TailCapture(1024, 0).read(new ByteArrayInputStream(all));
        assertFalse(c.logTruncated());
        assertArrayEquals(all, Files.readAllBytes(c.log()));
        Files.delete(c.log());
    }

    @Test
    void awaitGivesUpOnAStreamThatNeverEnds() throws Exception {
        PipedOutputStream w = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(w, 64 * 1024);
        w.write("early\n".getBytes(StandardCharsets.UTF_8));
        w.flush();
        TailCapture c = new TailCapture(1024);
        CompletableFuture<TailCapture> reader = c.start(in);
        long t0 = System.nanoTime();
        Thread.sleep(200);
        assertFalse(c.await(reader, in, Duration.ofMillis(300)));
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L);
        assertEquals("early\n", c.text());
    }

    @Test
    void returnsWhenTheShellExitsWhileABackgroundChildHoldsTheOutput() throws Exception {
        LocalDirSandboxExecutor box = new LocalDirSandboxExecutor(config(tmp));
        String cmd = WINDOWS ? "start /b ping -n 9 127.0.0.1 & echo started" : "sleep 8 & echo started";
        long t0 = System.currentTimeMillis();
        ExecutionResult r = box.execute("bg1", cmd, Duration.ofSeconds(60));
        long took = System.currentTimeMillis() - t0;
        assertEquals(0, r.exitCode(), r.stderr());
        assertFalse(r.timedOut());
        assertTrue(r.stdout().contains("started"), r.stdout());
        assertTrue(took < 6_000, "waited for the background child: " + took + " ms");
        if (WINDOWS) Thread.sleep(Math.max(0, 9_000 - took)); // the child's cwd is in the temp dir: let it end
    }

    @Test
    void dockerRunnerKeepsOnlyTheTail() throws Exception {
        List<String> argv = WINDOWS
                ? List.of("cmd", "/c", "for /L %i in (1,1,5000) do @echo line-%i")
                : List.of("sh", "-c", "i=1; while [ $i -le 5000 ]; do echo line-$i; i=$((i+1)); done");
        DockerCommandRunner.Result c = new ProcessDockerCommandRunner()
                .run(argv, Duration.ofSeconds(60), 1024, 2048);
        assertEquals(0, c.exitCode());
        assertTrue(c.stdout().length() <= 1024);
        assertTrue(c.stdout().strip().endsWith("line-5000"), c.stdout());
        assertTrue(c.omittedLines() > 0);
        assertNotNull(c.log());
        assertTrue(Files.size(c.log()) <= 2048 + 40);
        Files.delete(c.log());
    }

    // ── links ─────────────────────────────────────────────────────────────────

    private final List<Path> links = new ArrayList<>();

    /** Removes the links left behind, so the temp dir cleanup never walks through one. */
    @AfterEach
    void removeLinks() throws IOException {
        for (Path l : links) {
            if (Links.isLink(l)) Files.delete(l);
        }
    }

    /** A junction on Windows, a symbolic link elsewhere; the test is skipped when neither can be made. */
    private void link(Path link, Path target) throws Exception {
        links.add(link);
        if (WINDOWS) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            Assumptions.assumeTrue(p.waitFor() == 0 && Files.exists(link), "cannot create a junction");
        } else {
            try {
                Files.createSymbolicLink(link, target);
            } catch (IOException | UnsupportedOperationException e) {
                Assumptions.assumeTrue(false, "symlinks not permitted: " + e);
            }
        }
    }

    private Path outside() throws IOException {
        Path out = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(out.resolve("precious.txt"), "keep");
        return out;
    }

    @Test
    void linkIsDetected() throws Exception {
        Path out = outside();
        Path dir = Files.createDirectories(tmp.resolve("d"));
        link(dir.resolve("j"), out);
        assertTrue(Links.isLink(dir.resolve("j")));
        assertFalse(Links.isLink(dir));
        assertFalse(Links.isLink(out.resolve("precious.txt")));
        assertFalse(Links.isLink(dir.resolve("missing")));
    }

    @Test
    void deletingAChatRemovesTheLinkNotItsTarget() throws Exception {
        Path out = outside();
        ChatDirSandboxExecutor box = new ChatDirSandboxExecutor(config(tmp.resolve("x")), tmp.resolve("chats"));
        Path work = box.workDir("c1");
        Files.createDirectories(work.resolve("sub"));
        Files.writeString(work.resolve("sub/f.txt"), "f");
        link(work.resolve("sub/shared"), out);
        box.deleteWorkDir("c1");
        assertFalse(Files.exists(work, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(out.resolve("precious.txt")));
    }

    @Test
    void cwdScratchDeleteDoesNotFollowLinks() throws Exception {
        Path out = outside();
        Path project = Files.createDirectories(tmp.resolve("project"));
        CwdSandboxExecutor box = new CwdSandboxExecutor(config(tmp.resolve("x")), project);
        Path scratch = box.scratchDir("c1", "main");
        link(scratch.resolve("j"), out);
        box.deleteWorkDir("c1");
        assertEquals("keep", Files.readString(out.resolve("precious.txt")));
        assertTrue(Files.isDirectory(project));
    }

    @Test
    void defaultDeleteWorkDirDoesNotFollowLinks() throws Exception {
        Path out = outside();
        LocalDirSandboxExecutor box = new LocalDirSandboxExecutor(config(tmp.resolve("base")));
        link(box.workDir("c1").resolve("j"), out);
        box.deleteWorkDir("c1");
        assertEquals("keep", Files.readString(out.resolve("precious.txt")));
    }

    @Test
    void packSkipsLinkedDirectories() throws Exception {
        Path out = outside();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "a");
        link(src.resolve("shared"), out);
        Path dst = tmp.resolve("dst");
        assertEquals(1, WorkspaceArchive.unpack(WorkspaceArchive.pack(src), dst));
        assertFalse(Files.exists(dst.resolve("shared")));
        Path onlyLink = Files.createDirectories(tmp.resolve("only-link"));
        link(onlyLink.resolve("shared"), out);
        assertFalse(WorkspaceArchive.hasContent(onlyLink));
    }

    @Test
    void unpackRefusesToWriteThroughALinkedDirectory() throws Exception {
        Path out = outside();
        Path project = Files.createDirectories(tmp.resolve("project"));
        link(project.resolve("shared"), out);
        var zip = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(zip)) {
            z.putNextEntry(new java.util.zip.ZipEntry("shared/"));
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("shared/precious.txt"));
            z.write("overwritten".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        IOException e = assertThrows(IOException.class, () -> WorkspaceArchive.unpack(zip.toByteArray(), project));
        assertTrue(e.getMessage().contains("link"), e.getMessage());
        assertEquals("keep", Files.readString(out.resolve("precious.txt")));
    }

    @Test
    void unpackReplacesAFileLinkInsteadOfFollowingIt() throws Exception {
        Path out = outside();
        Path project = Files.createDirectories(tmp.resolve("project"));
        try {
            Files.createSymbolicLink(project.resolve("f.txt"), out.resolve("precious.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symlinks not permitted: " + e);
        }
        var zip = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(zip)) {
            z.putNextEntry(new java.util.zip.ZipEntry("f.txt"));
            z.write("new".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertEquals(1, WorkspaceArchive.unpack(zip.toByteArray(), project));
        assertFalse(Files.isSymbolicLink(project.resolve("f.txt")));
        assertEquals("new", Files.readString(project.resolve("f.txt")));
        assertEquals("keep", Files.readString(out.resolve("precious.txt")));
    }

    // ── scratch jail ──────────────────────────────────────────────────────────

    /** Records the calls; paths are mapped as the daemon backend maps them. */
    static final class RecordingBackend implements FileBackend {
        final RemoteFileBackend layout = new RemoteFileBackend(() -> {
            throw new IOException("no daemon");
        }, "/workspace", "/workspace/.jllm/scratchpad", "main");
        final List<String> moves = new ArrayList<>();
        List<String> globHits = List.of();

        @Override
        public String workRoot() {
            return layout.workRoot();
        }

        @Override
        public String scratchRoot() {
            return layout.scratchRoot();
        }

        @Override
        public Target readTarget(String path) {
            return layout.readTarget(path);
        }

        @Override
        public Target writeTarget(String path, boolean planMode) {
            return layout.writeTarget(path, planMode);
        }

        @Override
        public Stat stat(Target t) {
            return null;
        }

        @Override
        public byte[] read(Target t, long offset, long limit) {
            return new byte[0];
        }

        @Override
        public void write(Target t, byte[] data, boolean append) {
        }

        @Override
        public List<String> glob(String pattern) {
            return globHits;
        }

        @Override
        public List<String> grep(String pattern, String path, String glob) {
            return globHits;
        }

        @Override
        public JsonNode list(Target t, boolean all) {
            return null;
        }

        @Override
        public String tree(Target t, int depth) {
            return "";
        }

        @Override
        public void mkdir(Target t) {
        }

        @Override
        public void move(Root root, String from, String to) {
            moves.add(root + " " + from + " " + to);
        }

        @Override
        public void delete(Target t) {
        }
    }

    @Test
    void scratchJailDropsHitsOutsideTheScratchDirectory() throws Exception {
        RecordingBackend inner = new RecordingBackend();
        inner.globHits = List.of(".jllm/scratchpad/main/a.txt", "src/Secret.java", ".jllm/scratchpad/other/b");
        ScratchJailBackend jail = new ScratchJailBackend(inner);
        assertEquals(List.of("a.txt"), jail.glob("**"));
        inner.globHits = List.of(".jllm/scratchpad/main/a.txt:1:x", "src/Secret.java:3:key");
        assertEquals(List.of("a.txt:1:x"), jail.grep("x", null, null));
    }

    @Test
    void scratchJailMoveStaysInTheScratchDirectory() throws Exception {
        RecordingBackend inner = new RecordingBackend();
        ScratchJailBackend jail = new ScratchJailBackend(inner);
        jail.move(FileBackend.Root.WORKSPACE, "a.txt", "d/b.txt");
        assertEquals(List.of("SCRATCH main/a.txt main/d/b.txt"), inner.moves);
        assertThrows(IllegalArgumentException.class, () -> jail.move(FileBackend.Root.WORKSPACE, "../../x", "y"));
        assertThrows(IllegalArgumentException.class, () -> jail.move(FileBackend.Root.WORKSPACE, "a", "/workspace/src/A.java"));
        assertEquals(1, inner.moves.size());
    }
}

package org.kendar.llm.harness.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.sandbox.FileBackend;
import org.kendar.llm.harness.sandbox.SandboxUnavailableException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;
import org.junit.jupiter.api.function.Executable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** RemoteFs against an in-memory backend: jail, hidden directory, errors mapping, listing, modification, zip. */
class RemoteFsTest {

    /** Directories are the keys ending in "/"; "." is always a directory. */
    private static final class Mem implements FileBackend {
        final TreeMap<String, byte[]> files = new TreeMap<>();
        final Set<String> dirs = new TreeSet<>();
        final Set<String> links = new HashSet<>();
        RuntimeException failure;
        IOException ioFailure;

        @Override public String workRoot() { return "/w"; }
        @Override public String scratchRoot() { return "/s"; }
        @Override public Target readTarget(String path) { return new Target(Root.WORKSPACE, path); }
        @Override public Target writeTarget(String path, boolean planMode) { return new Target(Root.WORKSPACE, path); }

        private void check() throws IOException {
            if (failure != null) throw failure;
            if (ioFailure != null) throw ioFailure;
        }

        @Override
        public Stat stat(Target t) throws IOException {
            check();
            if (t.path().equals(".") || dirs.contains(t.path())) return new Stat(true, "dir", 0, 0, null);
            byte[] f = files.get(t.path());
            return f == null ? new Stat(false, null, 0, 0, null) : new Stat(true, "file", f.length, 0, null);
        }

        @Override
        public byte[] read(Target t, long offset, long limit) throws IOException {
            check();
            byte[] f = files.get(t.path());
            if (f == null) throw new NoSuchFileException(t.path());
            int from = (int) Math.min(offset, f.length);
            long end = limit < 0 ? f.length : Math.min(f.length, from + limit);
            return Arrays.copyOfRange(f, from, (int) end);
        }

        @Override
        public void write(Target t, byte[] data, boolean append) throws IOException {
            check();
            byte[] old = append ? files.getOrDefault(t.path(), new byte[0]) : new byte[0];
            byte[] n = Arrays.copyOf(old, old.length + data.length);
            System.arraycopy(data, 0, n, old.length, data.length);
            files.put(t.path(), n);
        }

        @Override
        public JsonNode list(Target t, boolean all) throws IOException {
            check();
            ObjectNode out = new ObjectMapper().createObjectNode();
            var entries = out.putArray("entries");
            String prefix = t.path().equals(".") ? "" : t.path() + "/";
            for (String d : dirs) {
                if (d.startsWith(prefix) && !d.substring(prefix.length()).contains("/") && d.length() > prefix.length()) {
                    entries.addObject().put("name", d.substring(prefix.length())).put("dir", true);
                }
            }
            files.forEach((k, v) -> {
                if (k.startsWith(prefix) && !k.substring(prefix.length()).contains("/")) {
                    entries.addObject().put("name", k.substring(prefix.length())).put("dir", false)
                            .put("size", v.length).put("link", links.contains(k));
                }
            });
            return out;
        }

        @Override public List<String> glob(String pattern) throws IOException {
            check();
            List<String> all = new ArrayList<>(files.keySet());
            return all;
        }
        @Override public List<String> grep(String pattern, String path, String glob) { return List.of(); }
        @Override public String tree(Target t, int depth) { return "tree:" + t.path() + ":" + depth; }
        @Override public void mkdir(Target t) { dirs.add(t.path()); }
        @Override public void move(Root root, String from, String to) {
            byte[] f = files.remove(from);
            if (f != null) files.put(to, f);
        }
        @Override public void delete(Target t) { files.remove(t.path()); dirs.remove(t.path()); }
    }

    private Mem mem;
    private RemoteFs fs;

    @BeforeEach
    void setUp() {
        mem = new Mem();
        mem.dirs.add("src");
        mem.dirs.add("src/main");
        mem.dirs.add(".jllm");
        mem.files.put("a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        mem.files.put("B.txt", "beta".getBytes(StandardCharsets.UTF_8));
        mem.files.put("src/main/X.java", "class X {}".getBytes(StandardCharsets.UTF_8));
        mem.files.put(".jllm/secret", "s".getBytes(StandardCharsets.UTF_8));
        fs = new RemoteFs(mem, "workspace");
    }

    private static void status(int expected, Executable call) {
        var e = assertThrows(FsException.class, call);
        assertEquals(expected, e.status());
    }

    // ── root and paths ────────────────────────────────────────────────────────

    @Test
    void rootNameIsValidated() {
        assertDoesNotThrow(() -> new RemoteFs(mem, null));
        assertDoesNotThrow(() -> new RemoteFs(mem, "scratch"));
        status(400, () -> new RemoteFs(mem, "other"));
    }

    @Test
    void pathsOutsideTheRootAreRejected() {
        status(400, () -> fs.read("../etc/passwd"));
        status(400, () -> fs.read("a/../../x"));
        status(400, () -> fs.read("/etc/passwd"));
        status(400, () -> fs.read("..\\x"));
    }

    @Test
    void hiddenDirectoryIsNotFoundInTheWorkspaceOnly() {
        status(404, () -> fs.read(".jllm/secret"));
        status(404, () -> fs.read("x/.JLLM/y"));
        mem.files.put(".jllm/secret", "s".getBytes(StandardCharsets.UTF_8));
        assertEquals("s", new RemoteFs(mem, "scratch").read(".jllm/secret").get("content"));
    }

    @Test
    void pathsAreNormalized() {
        assertEquals("alpha", fs.read("./a.txt").get("content"));
        assertEquals("src/main/X.java", fs.read("src//main/./X.java").get("path"));
        assertEquals("src/main/X.java", fs.read("src\\main\\X.java").get("path"));
    }

    // ── list / glob / tree ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void listSortsDirectoriesFirstCaseInsensitiveAndHidesTheHarnessDir() {
        Map<String, Object> l = fs.list("");
        List<Map<String, Object>> entries = (List<Map<String, Object>>) l.get("entries");
        assertEquals(List.of("src", "a.txt", "B.txt"), entries.stream().map(e -> e.get("name")).toList());
        assertEquals("", l.get("path"));
        assertFalse(l.containsKey("more"));
        assertEquals(5L, entries.get(1).get("size"));
        assertEquals(0L, entries.get(0).get("size"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listCompactsSingleChildDirectoryChains() {
        Map<String, Object> l = fs.list("");
        var src = ((List<Map<String, Object>>) l.get("entries")).get(0);
        var chain = (List<Map<String, String>>) src.get("chain");
        assertEquals(List.of("src", "main"), chain.stream().map(c -> c.get("name")).toList());
        assertEquals("src/main", chain.get(1).get("path"));
    }

    @Test
    void listOfAFileOrMissingDirectoryFails() {
        status(404, () -> fs.list("a.txt"));
    }

    @Test
    void globRequiresPatternAndHidesHarnessFiles() {
        status(400, () -> fs.glob(" "));
        status(400, () -> fs.glob(null));
        assertEquals(List.of("B.txt", "a.txt", "src/main/X.java"), fs.glob("**/*"));
    }

    @Test
    void treeClampsDepth() {
        assertEquals("tree:src:1", fs.tree("src", 0));
        assertEquals("tree:src:10", fs.tree("src", 99));
        assertEquals("tree:.:3", fs.tree("", 3));
    }

    // ── read / write / create ─────────────────────────────────────────────────

    @Test
    void readRejectsDirectoriesBinaryAndLargeFiles() {
        status(404, () -> fs.read("src"));
        status(404, () -> fs.read("missing.txt"));
        mem.files.put("bin", new byte[]{1, 0, 2});
        status(415, () -> fs.read("bin"));
        mem.files.put("big", new byte[(int) RemoteFs.MAX_TEXT_BYTES + 1]);
        status(413, () -> fs.read("big"));
    }

    @Test
    void writeReplacesExistingFilesOnly() {
        fs.write("a.txt", "new");
        assertEquals("new", new String(mem.files.get("a.txt"), StandardCharsets.UTF_8));
        status(404, () -> fs.write("nope.txt", "x"));
        status(400, () -> fs.write("src", "x"));
        status(400, () -> fs.write("", "x"));
    }

    @Test
    void createMakesFilesAndDirectories() {
        assertEquals("src/n.txt", fs.create("src", "n.txt", false, "hi"));
        assertEquals("hi", new String(mem.files.get("src/n.txt"), StandardCharsets.UTF_8));
        assertEquals("d2", fs.create("", "d2", true, null));
        assertTrue(mem.dirs.contains("d2"));
    }

    @Test
    void createValidatesParentNameAndConflicts() {
        status(404, () -> fs.create("a.txt", "n", false, ""));
        status(409, () -> fs.create("", "a.txt", false, ""));
        for (String bad : new String[]{"", " ", "a/b", "a\\b", ".", "..", ".jllm", ".JLLM"}) {
            status(400, () -> fs.create("", bad, false, ""));
        }
        status(400, () -> fs.create("", null, false, ""));
    }

    @Test
    void uploadHonorsOverwrite() {
        status(409, () -> fs.upload("", "a.txt", "z".getBytes(), false));
        assertEquals("a.txt", fs.upload("", "a.txt", "z".getBytes(), true));
        assertEquals("z", new String(mem.files.get("a.txt"), StandardCharsets.UTF_8));
        status(404, () -> fs.upload("a.txt", "n", "z".getBytes(), false));
    }

    // ── rename / delete ───────────────────────────────────────────────────────

    @Test
    void renameKeepsTheDirectory() {
        assertEquals("src/main/Y.java", fs.rename("src/main/X.java", "Y.java"));
        assertTrue(mem.files.containsKey("src/main/Y.java"));
        assertEquals("c.txt", fs.rename("a.txt", "c.txt"));
    }

    @Test
    void renameErrors() {
        status(400, () -> fs.rename("", "x"));
        status(404, () -> fs.rename("missing", "x"));
        status(409, () -> fs.rename("a.txt", "B.txt"));
        status(400, () -> fs.rename("a.txt", "x/y"));
    }

    @Test
    void deleteErrorsAndSuccess() {
        status(400, () -> fs.delete(""));
        status(404, () -> fs.delete("missing"));
        fs.delete("a.txt");
        assertFalse(mem.files.containsKey("a.txt"));
    }

    // ── errors mapping ────────────────────────────────────────────────────────

    @Test
    void backendFailuresBecomeHttpStatuses() {
        mem.failure = new SandboxUnavailableException("daemon down");
        status(503, () -> fs.read("a.txt"));
        mem.failure = new IllegalArgumentException("bad");
        status(400, () -> fs.read("a.txt"));
        mem.failure = null;
        mem.ioFailure = new IOException("pipe");
        var e = assertThrows(FsException.class, () -> fs.read("a.txt"));
        assertEquals(502, e.status());
        assertTrue(e.getMessage().contains("pipe"));
        mem.ioFailure = new NoSuchFileException("a.txt");
        status(404, () -> fs.read("a.txt"));
    }

    // ── download ──────────────────────────────────────────────────────────────

    @Test
    void bytesAndStat() {
        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), fs.bytes("a.txt"));
        assertTrue(fs.stat("a.txt").isFile());
        assertTrue(fs.stat("src").isDir());
        assertFalse(fs.stat("zzz").exists());
    }

    @Test
    void copyStopsWhenTheFileShrank() throws Exception {
        var out = new ByteArrayOutputStream();
        fs.copyTo("a.txt", 100, out); // claims 100 bytes, only 5 exist
        assertEquals("alpha", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void zipHasDirectoriesFilesAndSkipsHiddenAndLinks() throws Exception {
        mem.files.put("src/link", "l".getBytes(StandardCharsets.UTF_8));
        mem.links.add("src/link");
        var out = new ByteArrayOutputStream();
        fs.zip("", out);
        List<String> names = new ArrayList<>();
        try (var in = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) names.add(e.getName());
        }
        assertTrue(names.containsAll(List.of("a.txt", "B.txt", "src/", "src/main/", "src/main/X.java")), names.toString());
        assertTrue(names.stream().noneMatch(n -> n.contains(".jllm") || n.contains("link")), names.toString());
    }

    @Test
    void zipOfASubdirectoryHasRelativeNames() throws Exception {
        var out = new ByteArrayOutputStream();
        fs.zip("src/main", out);
        try (var in = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            assertEquals("X.java", in.getNextEntry().getName());
        }
    }
}

package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.sandbox.FileBackend;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Downloads, zips and uploads of daemon-mode chats move the data in chunks, never a whole file per call. */
class RemoteFsStreamingTest {

    /** In-memory backend recording the size of every transfer. */
    private static final class Fake implements FileBackend {
        final Map<String, byte[]> files = new HashMap<>();
        final List<Long> reads = new ArrayList<>();
        final List<Integer> writes = new ArrayList<>();

        @Override public String workRoot() { return "/workspace"; }
        @Override public String scratchRoot() { return "/scratch"; }
        @Override public Target readTarget(String path) { return new Target(Root.WORKSPACE, path); }
        @Override public Target writeTarget(String path, boolean planMode) { return new Target(Root.WORKSPACE, path); }

        @Override
        public Stat stat(Target t) {
            if (t.path().equals(".") || t.path().equals("d")) return new Stat(true, "dir", 0, 0, null);
            byte[] f = files.get(t.path());
            return f == null ? new Stat(false, null, 0, 0, null) : new Stat(true, "file", f.length, 0, null);
        }

        @Override
        public byte[] read(Target t, long offset, long limit) {
            assertTrue(limit >= 0 && limit <= RemoteFs.CHUNK, "bounded read, got " + limit);
            reads.add(limit);
            byte[] f = files.get(t.path());
            int from = (int) Math.min(offset, f.length);
            return Arrays.copyOfRange(f, from, (int) Math.min(f.length, from + limit));
        }

        @Override
        public void write(Target t, byte[] data, boolean append) {
            writes.add(data.length);
            byte[] old = append ? files.getOrDefault(t.path(), new byte[0]) : new byte[0];
            byte[] n = Arrays.copyOf(old, old.length + data.length);
            System.arraycopy(data, 0, n, old.length, data.length);
            files.put(t.path(), n);
        }

        @Override
        public JsonNode list(Target t, boolean all) {
            var m = new ObjectMapper();
            var out = m.createObjectNode();
            var entries = out.putArray("entries");
            String prefix = t.path().equals(".") ? "" : t.path() + "/";
            files.forEach((k, v) -> {
                if (k.startsWith(prefix) && !k.substring(prefix.length()).contains("/")) {
                    entries.addObject().put("name", k.substring(prefix.length())).put("dir", false).put("size", v.length);
                }
            });
            return out;
        }

        @Override public List<String> glob(String pattern) { return List.of(); }
        @Override public List<String> grep(String pattern, String path, String glob) { return List.of(); }
        @Override public String tree(Target t, int depth) { return ""; }
        @Override public void mkdir(Target t) { }
        @Override public void move(Root root, String from, String to) { }
        @Override public void delete(Target t) { }
    }

    private static byte[] data(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31);
        return b;
    }

    @Test
    void uploadDownloadAndZipAreChunked() throws Exception {
        Fake fb = new Fake();
        RemoteFs fs = new RemoteFs(fb, "workspace");
        byte[] big = data(RemoteFs.CHUNK * 2 + 123);
        fs.upload("d", "big.bin", new ByteArrayInputStream(big), false);
        assertArrayEquals(big, fb.files.get("d/big.bin"));
        assertEquals(List.of(RemoteFs.CHUNK, RemoteFs.CHUNK, 123), fb.writes);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fs.copyTo("d/big.bin", big.length, out);
        assertArrayEquals(big, out.toByteArray());
        assertEquals(3, fb.reads.size());

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        fs.zip("d", zip);
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip.toByteArray()))) {
            assertEquals("big.bin", in.getNextEntry().getName());
            assertArrayEquals(big, in.readAllBytes());
        }
    }

    @Test
    void emptyUploadCreatesAnEmptyFile() {
        Fake fb = new Fake();
        new RemoteFs(fb, "workspace").upload("", "empty.txt", new ByteArrayInputStream(new byte[0]), false);
        assertArrayEquals(new byte[0], fb.files.get("empty.txt"));
    }
}

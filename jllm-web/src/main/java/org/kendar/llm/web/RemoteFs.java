package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.sandbox.FileBackend;
import org.kendar.llm.harness.sandbox.FileBackend.Root;
import org.kendar.llm.harness.sandbox.FileBackend.Target;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The file explorer of {@link FilesController} for a chat whose files live in a docker daemon-mode container: the same
 * results as the host implementation, every call proxied to the daemon through a {@link FileBackend}. Paths are
 * relative to the chosen root and jailed twice (here, and by the daemon).
 */
final class RemoteFs {

    static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    private static final String HIDDEN_DIR = ".jllm";
    private static final int MAX_CHAIN = 16;
    private static final int MAX_ZIP_ENTRIES = 20_000;

    private final FileBackend fb;
    private final Root root;

    RemoteFs(FileBackend fb, String rootName) {
        this.fb = fb;
        this.root = switch (rootName == null ? "workspace" : rootName) {
            case "workspace" -> Root.WORKSPACE;
            case "scratch" -> Root.SCRATCH;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "root must be workspace or scratch");
        };
    }

    // ── paths ─────────────────────────────────────────────────────────────────

    /** Relative, forward-slash path with no {@code ..}; empty = the root. */
    private String clean(String rel) {
        String r = rel == null ? "" : rel.replace('\\', '/');
        if (r.startsWith("/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path outside the project");
        List<String> out = new ArrayList<>();
        for (String seg : r.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path outside the project");
            out.add(seg);
        }
        if (root == Root.WORKSPACE && out.stream().anyMatch(HIDDEN_DIR::equalsIgnoreCase)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return String.join("/", out);
    }

    private Target target(String rel) {
        String p = clean(rel);
        return new Target(root, p.isEmpty() ? "." : p);
    }

    private static String childName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".")
                || name.equals("..") || name.equalsIgnoreCase(HIDDEN_DIR)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        }
        return name;
    }

    private static String join(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    private boolean hidden(String relPath) {
        if (root != Root.WORKSPACE) return false;
        for (String seg : relPath.split("/")) if (HIDDEN_DIR.equalsIgnoreCase(seg)) return true;
        return false;
    }

    private <T> T io(IoCall<T> call) {
        try {
            return call.run();
        } catch (org.kendar.llm.harness.sandbox.SandboxUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sandbox: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T run() throws IOException;
    }

    // ── browse ────────────────────────────────────────────────────────────────

    Map<String, Object> list(String path) {
        String dir = clean(path);
        JsonNode l = io(() -> fb.list(new Target(root, dir.isEmpty() ? "." : dir), true));
        if (!io(() -> fb.stat(new Target(root, dir.isEmpty() ? "." : dir))).isDir()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (JsonNode e : l.path("entries")) {
            String name = e.path("name").asText();
            String rel = join(dir, name);
            if (hidden(rel)) continue;
            boolean isDir = e.path("dir").asBoolean(false);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", rel);
            m.put("dir", isDir);
            if (isDir) m.put("chain", chain(rel, name));
            m.put("size", isDir ? 0 : e.path("size").asLong());
            m.put("modified", e.path("mtime").asLong());
            entries.add(m);
        }
        entries.sort(Comparator.<Map<String, Object>, Boolean>comparing(e -> !(Boolean) e.get("dir"))
                .thenComparing(e -> ((String) e.get("name")).toLowerCase()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", dir);
        out.put("entries", entries);
        if (l.path("more").asInt(0) > 0) out.put("more", l.path("more").asInt());
        return out;
    }

    /** Same compaction as the host listing: a directory holding only one sub-directory is part of the same row. */
    private List<Map<String, String>> chain(String relPath, String name) {
        List<Map<String, String>> out = new ArrayList<>();
        String cur = relPath;
        String curName = name;
        for (int i = 0; i < MAX_CHAIN; i++) {
            out.add(Map.of("name", curName, "path", cur));
            JsonNode l;
            try {
                l = fb.list(new Target(root, cur), true);
            } catch (IOException | RuntimeException e) {
                break;
            }
            List<JsonNode> kids = new ArrayList<>();
            for (JsonNode k : l.path("entries")) if (!hidden(join(cur, k.path("name").asText()))) kids.add(k);
            if (kids.size() != 1 || !kids.get(0).path("dir").asBoolean(false) || l.path("more").asInt(0) > 0) break;
            curName = kids.get(0).path("name").asText();
            cur = join(cur, curName);
        }
        return out;
    }

    List<String> glob(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pattern is required");
        }
        return io(() -> fb.glob(pattern)).stream().filter(p -> !hidden(p)).toList();
    }

    String tree(String path, int depth) {
        return io(() -> fb.tree(target(path), Math.clamp(depth, 1, 10)));
    }

    // ── read / modify ─────────────────────────────────────────────────────────

    Map<String, Object> read(String path) {
        Target t = target(path);
        FileBackend.Stat st = io(() -> fb.stat(t));
        if (!st.isFile()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a file");
        if (st.size() > MAX_TEXT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large to edit");
        }
        byte[] bytes = io(() -> fb.read(t, 0, -1));
        for (byte b : bytes) {
            if (b == 0) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Binary file");
        }
        return Map.of("path", clean(path), "content", new String(bytes, StandardCharsets.UTF_8));
    }

    Map<String, Object> write(String path, String content) {
        Target t = target(path);
        if (clean(path).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a file");
        FileBackend.Stat st = io(() -> fb.stat(t));
        if (!st.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        if (st.isDir()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a file");
        io(() -> {
            fb.write(t, content.getBytes(StandardCharsets.UTF_8), false);
            return null;
        });
        return Map.of("path", clean(path));
    }

    String create(String parent, String name, boolean dir, String content) {
        String p = clean(parent);
        if (!io(() -> fb.stat(new Target(root, p.isEmpty() ? "." : p))).isDir()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        }
        String rel = join(p, childName(name));
        Target t = new Target(root, rel);
        if (io(() -> fb.stat(t)).exists()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists");
        io(() -> {
            if (dir) fb.mkdir(t);
            else fb.write(t, content.getBytes(StandardCharsets.UTF_8), false);
            return null;
        });
        return rel;
    }

    String upload(String parent, String name, byte[] data, boolean overwrite) {
        return upload(parent, name, new java.io.ByteArrayInputStream(data), overwrite);
    }

    /** Sends the upload to the daemon in chunks of {@link #CHUNK} bytes (first write replaces, the next ones append). */
    String upload(String parent, String name, java.io.InputStream data, boolean overwrite) {
        String p = clean(parent);
        if (!io(() -> fb.stat(new Target(root, p.isEmpty() ? "." : p))).isDir()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        }
        String rel = join(p, childName(name));
        Target t = new Target(root, rel);
        if (!overwrite && io(() -> fb.stat(t)).exists()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + name);
        }
        io(() -> {
            boolean append = false;
            while (true) {
                byte[] chunk = data.readNBytes(CHUNK);
                if (chunk.length == 0 && append) break;
                fb.write(t, chunk, append);
                append = true;
                if (chunk.length < CHUNK) break;
            }
            return null;
        });
        return rel;
    }

    /** Bytes moved per daemon call by downloads, zips and uploads. */
    static final int CHUNK = 1024 * 1024;

    String rename(String path, String newName) {
        String src = clean(path);
        if (src.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot rename the root");
        if (!io(() -> fb.stat(new Target(root, src))).exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        int slash = src.lastIndexOf('/');
        String dst = join(slash < 0 ? "" : src.substring(0, slash), childName(newName));
        if (io(() -> fb.stat(new Target(root, dst))).exists()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists");
        }
        io(() -> {
            fb.move(root, src, dst);
            return null;
        });
        return dst;
    }

    void delete(String path) {
        String p = clean(path);
        if (p.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete the root");
        Target t = new Target(root, p);
        if (!io(() -> fb.stat(t)).exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        io(() -> {
            fb.delete(t);
            return null;
        });
    }

    // ── download ──────────────────────────────────────────────────────────────

    FileBackend.Stat stat(String path) {
        return io(() -> fb.stat(target(path)));
    }

    byte[] bytes(String path) {
        Target t = target(path);
        return io(() -> fb.read(t, 0, -1));
    }

    /** Streams a file of {@code size} bytes to {@code out}, {@link #CHUNK} bytes per daemon call. */
    void copyTo(String path, long size, OutputStream out) throws IOException {
        copy(target(path), size, out);
    }

    private void copy(Target t, long size, OutputStream out) throws IOException {
        long offset = 0;
        while (offset < size) {
            byte[] chunk = fb.read(t, offset, Math.min(CHUNK, size - offset));
            if (chunk.length == 0) break; // the file shrank meanwhile
            out.write(chunk);
            offset += chunk.length;
        }
    }

    /** Zip of a directory: hidden entries and symbolic links are left out. */
    void zip(String path, OutputStream out) throws IOException {
        String base = clean(path);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            int[] count = {0};
            addDir(zip, base, "", count);
        }
    }

    private void addDir(ZipOutputStream zip, String dirRel, String prefix, int[] count) throws IOException {
        JsonNode l = fb.list(new Target(root, dirRel.isEmpty() ? "." : dirRel), true);
        for (JsonNode e : l.path("entries")) {
            String name = e.path("name").asText();
            String rel = join(dirRel, name);
            if (hidden(rel) || e.path("link").asBoolean(false)) continue;
            if (++count[0] > MAX_ZIP_ENTRIES) throw new IOException("Too many entries to zip");
            if (e.path("dir").asBoolean(false)) {
                zip.putNextEntry(new ZipEntry(prefix + name + "/"));
                zip.closeEntry();
                addDir(zip, rel, prefix + name + "/", count);
            } else {
                zip.putNextEntry(new ZipEntry(prefix + name));
                Target t = new Target(root, rel);
                copy(t, e.hasNonNull("size") ? e.get("size").asLong() : fb.stat(t).size(), zip);
                zip.closeEntry();
            }
        }
    }
}

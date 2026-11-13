package org.kendar.llm.web.commons;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.sandbox.FileBackend;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal file explorer over the work directory of one chat. All paths are relative to it and jailed inside it. */
@RestController
@RequestMapping("/api/chat/{chatId}/fs")
public class FilesController {

    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    /** Internal harness data (history, checkpoints, scratch); never exposed through the web UI. */
    private static final String HIDDEN_DIR = ".jllm";

    private final AgentHarness harness;

    public FilesController(AgentHarness harness) {
        this.harness = harness;
    }

    private Path root(String chatId) {
        if (harness.sessions().find(chatId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown chat");
        }
        return harness.sandbox().workDir(chatId).toAbsolutePath().normalize();
    }

    // ── browse ────────────────────────────────────────────────────────────────

    @GetMapping
    public Map<String, Object> list(@PathVariable String chatId, @RequestParam(defaultValue = "") String path,
                                    @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) return rfs.list(path);
        Path root = root(chatId);
        Path dir = resolve(root, path);
        if (!Files.isDirectory(dir)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (isHidden(root, p)) continue;
                boolean isDir = Files.isDirectory(p);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", p.getFileName().toString());
                e.put("path", rel(root, p));
                e.put("dir", isDir);
                if (isDir) e.put("chain", chain(root, p));
                e.put("size", isDir ? 0 : safeSize(p));
                e.put("modified", safeModified(p));
                entries.add(e);
            }
        }
        entries.sort(Comparator.<Map<String, Object>, Boolean>comparing(e -> !(Boolean) e.get("dir"))
                .thenComparing(e -> ((String) e.get("name")).toLowerCase()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", rel(root, dir));
        out.put("entries", entries);
        return out;
    }

    /**
     * GitHub-style compaction: while a directory holds nothing but one sub-directory, that sub-directory is part of the
     * same row. Returns every segment (including the entry itself) so each can be navigated separately.
     */
    private List<Map<String, String>> chain(Path root, Path dir) {
        List<Map<String, String>> out = new ArrayList<>();
        Path cur = dir;
        for (int i = 0; i < 64; i++) {
            out.add(Map.of("name", cur.getFileName().toString(), "path", rel(root, cur)));
            Path only = null;
            try (Stream<Path> s = Files.list(cur)) {
                List<Path> kids = s.filter(k -> !isHidden(root, k)).limit(2).toList();
                if (kids.size() == 1) only = kids.get(0);
            } catch (IOException e) {
                break;
            }
            if (only == null || Files.isSymbolicLink(only) || !Files.isDirectory(only)) break;
            cur = only;
        }
        return out;
    }

    // ── read / modify ─────────────────────────────────────────────────────────

    @GetMapping("/content")
    public Map<String, Object> read(@PathVariable String chatId, @RequestParam String path,
                                    @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) return rfs.read(path);
        Path root = root(chatId);
        Path f = resolve(root, path);
        if (!Files.isRegularFile(f)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a file");
        if (Files.size(f) > MAX_TEXT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large to edit");
        }
        byte[] bytes = Files.readAllBytes(f);
        for (byte b : bytes) {
            if (b == 0) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Binary file");
        }
        return Map.of("path", rel(root, f), "content", new String(bytes, StandardCharsets.UTF_8));
    }

    @PutMapping("/content")
    public Map<String, Object> write(@PathVariable String chatId, @RequestBody Map<String, String> body,
                                     @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) return rfs.write(required(body, "path"), body.getOrDefault("content", ""));
        Path root = root(chatId);
        Path f = resolve(root, required(body, "path"));
        if (f.equals(root) || Files.isDirectory(f)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a file");
        }
        if (!Files.exists(f)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        Files.writeString(f, body.getOrDefault("content", ""), StandardCharsets.UTF_8);
        return Map.of("path", rel(root, f));
    }

    // ── add ───────────────────────────────────────────────────────────────────

    /** Creates a file ({@code type=file}, optional {@code content}) or a directory ({@code type=dir}) inside {@code path}. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable String chatId, @RequestBody Map<String, String> body,
                                                      @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) {
            String created = rfs.create(body.getOrDefault("path", ""), required(body, "name"),
                    "dir".equals(body.get("type")), body.getOrDefault("content", ""));
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("path", created));
        }
        Path root = root(chatId);
        Path parent = resolve(root, body.getOrDefault("path", ""));
        if (!Files.isDirectory(parent)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        Path target = child(root, parent, required(body, "name"));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists");
        }
        if ("dir".equals(body.get("type"))) Files.createDirectory(target);
        else Files.writeString(target, body.getOrDefault("content", ""), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("path", rel(root, target)));
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@PathVariable String chatId, @RequestParam(defaultValue = "") String path,
                                      @RequestParam("files") List<MultipartFile> files,
                                      @RequestParam(defaultValue = "false") boolean overwrite,
                                      @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        List<String> names = new ArrayList<>();
        for (MultipartFile mf : files) names.add(uploadName(mf.getOriginalFilename()));
        if (rfs != null) {
            List<String> saved = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                try (var in = files.get(i).getInputStream()) {
                    saved.add(rfs.upload(path, names.get(i), in, overwrite));
                }
            }
            return Map.of("saved", saved);
        }
        Path root = root(chatId);
        Path dir = resolve(root, path);
        if (!Files.isDirectory(dir)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile mf = files.get(i);
            String name = names.get(i);
            Path target = child(root, dir, name);
            if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + name);
            }
            try (var in = mf.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            saved.add(rel(root, target));
        }
        return Map.of("saved", saved);
    }

    /** Characters no file name may hold (Windows rules, applied everywhere: the same project may be used on both). */
    private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile("[<>:\"|?*\\x00-\\x1f]");
    private static final java.util.regex.Pattern WINDOWS_DEVICE =
            java.util.regex.Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /**
     * The file name of an upload: the last segment of the client's name (browsers may send a path), refused with 400
     * when nothing usable is left or it holds reserved characters ({@code a:b}), is a device name or ends with a dot or
     * space.
     */
    static String uploadName(String original) {
        String o = original == null ? "" : original.replace('\\', '/');
        String name = o.substring(o.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || RESERVED.matcher(name).find()
                || WINDOWS_DEVICE.matcher(name).matches() || name.endsWith(".") || name.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name '" + original + "'");
        }
        return name;
    }

    // ── rename / delete ───────────────────────────────────────────────────────

    @PostMapping("/rename")
    public Map<String, Object> rename(@PathVariable String chatId, @RequestBody Map<String, String> body,
                                      @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) return Map.of("path", rfs.rename(required(body, "path"), required(body, "name")));
        Path root = root(chatId);
        Path src = resolve(root, required(body, "path"));
        if (src.equals(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot rename the root");
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        Path dst = child(root, src.getParent(), required(body, "name"));
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists");
        }
        Files.move(src, dst);
        return Map.of("path", rel(root, dst));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable String chatId, @RequestParam String path,
                                       @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) {
            rfs.delete(path);
            return ResponseEntity.noContent().build();
        }
        Path root = root(chatId);
        Path p = resolve(root, path);
        if (p.equals(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete the root");
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.delete(p);
        }
        return ResponseEntity.noContent().build();
    }

    // ── download ──────────────────────────────────────────────────────────────

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String chatId, @RequestParam String path,
                                                          @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) return downloadRemote(rfs, path);
        Path root = root(chatId);
        Path p = resolve(root, path);
        if (Files.isRegularFile(p)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(p.getFileName().toString()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(p))
                    .body(out -> {
                        try (var in = Files.newInputStream(p)) {
                            in.transferTo(out);
                        }
                    });
        }
        if (!Files.isDirectory(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        String base = p.equals(root) || p.getFileName() == null ? "project" : p.getFileName().toString();
        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out); Stream<Path> s = Files.walk(p)) {
                for (Path f : s.toList()) {
                    if (f.equals(p) || Files.isSymbolicLink(f) || isHidden(root, f)) continue;
                    String name = p.relativize(f).toString().replace('\\', '/');
                    if (Files.isDirectory(f)) {
                        zip.putNextEntry(new ZipEntry(name + "/"));
                    } else {
                        zip.putNextEntry(new ZipEntry(name));
                        Files.copy(f, zip);
                    }
                    zip.closeEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(base + ".zip"))
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    private ResponseEntity<StreamingResponseBody> downloadRemote(RemoteFs rfs, String path) {
        var st = rfs.stat(path);
        String name = path == null ? "" : path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (st.isFile()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(st.size())
                    .body(out -> rfs.copyTo(path, st.size(), out)); // chunk by chunk, never the whole file in memory
        }
        if (!st.isDir()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        String base = name.isEmpty() ? "project" : name;
        StreamingResponseBody body = out -> rfs.zip(path, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(base + ".zip"))
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    // ── daemon mode only ──────────────────────────────────────────────────────

    /** Files of the work dir matching a glob (docker daemon mode only). */
    @GetMapping("/glob")
    public List<String> glob(@PathVariable String chatId, @RequestParam String pattern) {
        RemoteFs rfs = remote(chatId, "workspace");
        if (rfs == null) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Needs docker daemon mode");
        return rfs.glob(pattern);
    }

    /** Capped recursive tree of a directory (docker daemon mode only). */
    @GetMapping(value = "/tree", produces = MediaType.TEXT_PLAIN_VALUE)
    public String tree(@PathVariable String chatId, @RequestParam(defaultValue = "") String path,
                       @RequestParam(defaultValue = "2") int depth, @RequestParam(name = "root", defaultValue = "workspace") String rootName) {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs == null) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Needs docker daemon mode");
        return rfs.tree(path, depth);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * The proxy to the container's daemon, or null when the chat's files are on the host. Checks the chat exists.
     * {@code root=scratch} exists only in daemon mode.
     */
    private RemoteFs remote(String chatId, String rootName) {
        if (harness.sessions().find(chatId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown chat");
        }
        FileBackend fb = harness.sandbox().files(chatId, null);
        if (fb == null) {
            if (!"workspace".equals(rootName)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "root=" + rootName + " needs docker daemon mode");
            }
            return null;
        }
        return new RemoteFs(fb, rootName);
    }

    private static String attachment(String name) {
        return ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString();
    }

    private static String required(Map<String, String> body, String key) {
        String v = body.get(key);
        if (v == null || v.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
        return v;
    }

    /** Resolves a relative path inside the root, rejecting escapes (including via symlinks). */
    private Path resolve(Path root, String rel) {
        String r = rel == null ? "" : rel.replace('\\', '/');
        Path p;
        try {
            p = root.resolve(r).normalize();
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        if (!p.startsWith(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path outside the project");
        if (isHidden(root, p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        jailCheck(root, p);
        return p;
    }

    /** True when the path is, or lies inside, the hidden harness directory of the root. */
    private boolean isHidden(Path root, Path p) {
        Path r = root.relativize(p);
        for (Path seg : r) if (HIDDEN_DIR.equalsIgnoreCase(seg.toString())) return true;
        return false;
    }

    private Path child(Path root, Path parent, String name) {
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        }
        Path p;
        try {
            p = parent.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        }
        if (!p.startsWith(root) || !parent.equals(p.getParent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        }
        if (isHidden(root, p)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        jailCheck(root, p);
        return p;
    }

    /** The deepest existing ancestor must really live under the real root (blocks symlink escapes). */
    private void jailCheck(Path root, Path p) {
        try {
            Path real = root.toRealPath();
            Path q = p;
            while (q != null && !Files.exists(q)) q = q.getParent();
            if (q != null && !q.toRealPath().startsWith(real)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path outside the project");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
    }

    private String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long safeModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}

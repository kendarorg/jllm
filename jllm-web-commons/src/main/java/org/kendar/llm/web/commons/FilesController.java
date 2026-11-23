package org.kendar.llm.web.commons;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.fs.FsException;
import org.kendar.llm.harness.fs.RemoteFs;
import org.kendar.llm.harness.sandbox.FileBackend;
import org.kendar.llm.harness.tools.AtomicFiles;
import org.kendar.llm.harness.tools.ExternalLinks;
import org.kendar.llm.harness.tools.PathJail;
import org.kendar.llm.harness.tools.PathJail.JailedPath;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
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
        JailedPath dir = resolve(root, path);
        if (!PathJail.isDirectory(dir)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PathJail.Entry en : PathJail.list(dir)) {
            JailedPath p = dir.child(en.name());
            if (isHidden(p)) continue;
            BasicFileAttributes a = en.attributes();
            if (en.isLink()) {
                try {
                    a = PathJail.stat(p); // a link inside the project shows what it points to
                } catch (IOException | IllegalArgumentException e) {
                    continue; // leaves the project, or dangling: not listed
                }
                if (a == null) continue;
            }
            boolean isDir = a.isDirectory();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", en.name());
            e.put("path", rel(p));
            e.put("dir", isDir);
            if (isDir && !en.isLink()) e.put("chain", chain(p));
            e.put("size", isDir ? 0 : a.size());
            e.put("modified", a.lastModifiedTime().toMillis());
            entries.add(e);
        }
        entries.sort(Comparator.<Map<String, Object>, Boolean>comparing(e -> !(Boolean) e.get("dir"))
                .thenComparing(e -> ((String) e.get("name")).toLowerCase()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", rel(dir));
        out.put("entries", entries);
        return out;
    }

    /**
     * GitHub-style compaction: while a directory holds nothing but one sub-directory, that sub-directory is part of the
     * same row. Returns every segment (including the entry itself) so each can be navigated separately.
     */
    private List<Map<String, String>> chain(JailedPath dir) {
        List<Map<String, String>> out = new ArrayList<>();
        JailedPath cur = dir;
        for (int i = 0; i < 64; i++) {
            out.add(Map.of("name", cur.fileName(), "path", rel(cur)));
            PathJail.Entry only = null;
            try {
                JailedPath here = cur;
                List<PathJail.Entry> kids = PathJail.list(cur).stream().filter(k -> !isHidden(here.child(k.name()))).limit(2)
                        .toList();
                if (kids.size() == 1) only = kids.get(0);
            } catch (IOException | IllegalArgumentException e) {
                break;
            }
            if (only == null || only.isLink() || !only.attributes().isDirectory()) break;
            cur = cur.child(only.name());
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
        JailedPath f = resolve(root, path);
        BasicFileAttributes st = PathJail.stat(f);
        if (st == null || !st.isRegularFile()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a file");
        if (st.size() > MAX_TEXT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large to edit");
        }
        byte[] bytes = PathJail.readAllBytes(f, MAX_TEXT_BYTES);
        for (byte b : bytes) {
            if (b == 0) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Binary file");
        }
        return Map.of("path", rel(f), "content", new String(bytes, StandardCharsets.UTF_8));
    }

    @PutMapping("/content")
    public Map<String, Object> write(@PathVariable String chatId, @RequestBody Map<String, String> body,
                                     @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) return rfs.write(required(body, "path"), body.getOrDefault("content", ""));
        Path root = root(chatId);
        JailedPath f = resolve(root, required(body, "path"));
        BasicFileAttributes st = PathJail.stat(f);
        if (f.components().isEmpty() || (st != null && st.isDirectory())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a file");
        }
        if (st == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        AtomicFiles.write(f, body.getOrDefault("content", "").getBytes(StandardCharsets.UTF_8));
        return Map.of("path", rel(f));
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
        JailedPath parent = resolve(root, body.getOrDefault("path", ""));
        if (!PathJail.isDirectory(parent)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        JailedPath target = child(parent, required(body, "name"));
        if (PathJail.statNoFollow(target) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists");
        try {
            if ("dir".equals(body.get("type"))) {
                PathJail.createDirectory(target);
            } else {
                try (OutputStream out = Channels.newOutputStream(
                        PathJail.openWrite(target, Set.of(StandardOpenOption.CREATE_NEW)))) {
                    out.write(body.getOrDefault("content", "").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (FileAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("path", rel(target)));
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
        JailedPath dir = resolve(root, path);
        if (!PathJail.isDirectory(dir)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory");
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile mf = files.get(i);
            String name = names.get(i);
            JailedPath target = child(dir, name);
            if (!overwrite && PathJail.statNoFollow(target) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + name);
            }
            try (var in = mf.getInputStream()) {
                AtomicFiles.write(target, in);
            }
            saved.add(rel(target));
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
        JailedPath src = resolve(root, required(body, "path"));
        if (src.components().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot rename the root");
        if (PathJail.statNoFollow(src) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        JailedPath dst = child(src.parent(), required(body, "name"));
        if (PathJail.statNoFollow(dst) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists");
        PathJail.rename(src, dst.fileName());
        return Map.of("path", rel(dst));
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
        JailedPath p = resolve(root, path);
        if (p.components().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete the root");
        if (PathJail.statNoFollow(p) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        PathJail.deleteTree(p);
        return ResponseEntity.noContent().build();
    }

    // ── download ──────────────────────────────────────────────────────────────

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String chatId, @RequestParam String path,
                                                          @RequestParam(name = "root", defaultValue = "workspace") String rootName) throws IOException {
        RemoteFs rfs = remote(chatId, rootName);
        if (rfs != null) return downloadRemote(rfs, path);
        Path root = root(chatId);
        JailedPath p = resolve(root, path);
        BasicFileAttributes st = PathJail.stat(p);
        if (st != null && st.isRegularFile()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(p.fileName()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(st.size())
                    .body(out -> {
                        try (var in = PathJail.newInputStream(p)) {
                            in.transferTo(out);
                        }
                    });
        }
        if (st == null || !st.isDirectory()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        String base = p.components().isEmpty() ? "project" : p.fileName();
        int skip = p.components().size();
        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                PathJail.walk(p, (f, a) -> {
                    if (f.components().size() <= skip) return PathJail.Action.CONTINUE;
                    if (isHidden(f)) return PathJail.Action.SKIP_DIRECTORY;
                    BasicFileAttributes own = PathJail.statNoFollow(f);
                    if (own == null || PathJail.isLink(own)) return PathJail.Action.CONTINUE; // links are not zipped
                    String name = String.join("/", f.components().subList(skip, f.components().size()));
                    if (a.isDirectory()) {
                        zip.putNextEntry(new ZipEntry(name + "/"));
                    } else {
                        zip.putNextEntry(new ZipEntry(name));
                        try (InputStream in = PathJail.newInputStream(f)) {
                            in.transferTo(zip);
                        }
                    }
                    zip.closeEntry();
                    return PathJail.Action.CONTINUE;
                });
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

    /** A failure of the docker file explorer, answered with the status it carries. */
    @ExceptionHandler(FsException.class)
    public void remoteFailure(FsException e, jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.sendError(e.status(), e.getMessage());
    }


    /** A link or path leaving the project, found while the jail did the I/O (also when swapped in meanwhile). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> outsideTheProject(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "Invalid path" : e.getMessage()));
    }

    /** Resolves a relative path inside the root, rejecting escapes (including via symlinks). */
    private JailedPath resolve(Path root, String rel) {
        String r = rel == null ? "" : rel.replace('\\', '/');
        JailedPath p;
        try {
            p = PathJail.resolve(root, r, ExternalLinks.NONE, false); // the web host never follows a link out of the project
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path outside the project");
        }
        if (isHidden(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        return p;
    }

    /** True when the path is, or lies inside (also through a link), the hidden harness directory of the root. */
    private boolean isHidden(JailedPath p) {
        for (String seg : p.components()) if (HIDDEN_DIR.equalsIgnoreCase(seg)) return true;
        List<String> real = PathJail.realComponents(p);
        if (real != null) for (String seg : real) if (HIDDEN_DIR.equalsIgnoreCase(seg)) return true;
        return false;
    }

    private JailedPath child(JailedPath parent, String name) {
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        }
        JailedPath p;
        try {
            p = parent.child(name);
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        }
        if (isHidden(p)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        return p;
    }

    private String rel(JailedPath p) {
        return String.join("/", p.components());
    }
}

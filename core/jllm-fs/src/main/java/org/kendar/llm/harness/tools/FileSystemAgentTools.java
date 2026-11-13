package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** File tools scoped to the per-chat work dir. Never exposed via MCP/REST. */
public class FileSystemAgentTools implements HarnessToolSet {

    private static final String GROUP = "Files";
    private static final int MAX_RESULTS = 200;
    /** Default of {@code files.maxReadBytes}: the most text one {@code read_file} returns and the largest file edited. */
    public static final long DEFAULT_MAX_READ_BYTES = 1_000_000;
    private static final long MAX_DOCUMENT_BYTES = 50_000_000;
    static final int MAX_READ_LINES = 2000;
    static final int MAX_READ_OUTPUT_BYTES = 50 * 1024;
    private static final java.util.Set<String> BINARY_EXTENSIONS = java.util.Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "jar", "class", "exe", "dll", "png", "jpg",
            "jpeg", "gif", "bmp", "ico", "mp3", "mp4", "7z", "gz", "tar");

    private final DocumentReaders readers;
    private final Checkpoints checkpoints;
    private final StorageQuota quota;
    private final long maxReadBytes;

    public FileSystemAgentTools() {
        this(null, null);
    }

    public FileSystemAgentTools(DocumentReaders readers) {
        this(readers, null);
    }

    public FileSystemAgentTools(DocumentReaders readers, Checkpoints checkpoints) {
        this(readers, checkpoints, StorageQuota.UNLIMITED);
    }

    public FileSystemAgentTools(DocumentReaders readers, Checkpoints checkpoints, StorageQuota quota) {
        this(readers, checkpoints, quota, DEFAULT_MAX_READ_BYTES);
    }

    /**
     * @param maxReadBytes {@code files.maxReadBytes}: the most text one {@code read_file} returns (larger files are
     *                     read by line range), the largest file {@code edit_file} changes and {@code grep} searches;
     *                     {@code <= 0}: the default
     */
    public FileSystemAgentTools(DocumentReaders readers, Checkpoints checkpoints, StorageQuota quota, long maxReadBytes) {
        this.readers = readers;
        this.checkpoints = checkpoints;
        this.quota = quota == null ? StorageQuota.UNLIMITED : quota;
        this.maxReadBytes = maxReadBytes > 0 ? maxReadBytes : DEFAULT_MAX_READ_BYTES;
    }

    private void checkpoint(ToolContext ctx, Path p) {
        if (checkpoints != null) checkpoints.record(ctx, p, quota);
    }

    @Override
    public List<HarnessTool> tools() {
        return List.of(readFile(), writeFile(), editFile(), glob(), ls(), grep(), grepCode());
    }

    /** Resolves a path for reading: inside the work dir, or an absolute path inside the scratch dir. */
    public static Path jail(ToolContext ctx, String path) {
        Path scratch = scratchRoot(ctx);
        if (scratch != null && Path.of(path).isAbsolute() && Path.of(path).normalize().startsWith(scratch)) {
            return resolveIn(scratch, path);
        }
        return resolveIn(ctx.workDir(), path);
    }

    /**
     * Resolves a path for writing. In plan mode the work dir is read-only: relative paths go to the scratch dir and
     * absolute paths must already be inside it.
     */
    public static Path jailWrite(ToolContext ctx, String path) {
        Path p;
        if (!ctx.readOnlyWork()) {
            p = jail(ctx, path);
        } else {
            Path scratch = scratchRoot(ctx);
            if (Path.of(path).isAbsolute() && !Path.of(path).normalize().startsWith(scratch)) {
                throw new IllegalArgumentException("Plan mode: the working directory is read-only; write under "
                        + scratch + " instead");
            }
            p = resolveIn(scratch, path);
        }
        if (Checkpoints.isCheckpointPath(ctx.workDir(), p)) {
            throw new IllegalArgumentException("Checkpoint data cannot be written: " + path);
        }
        return p;
    }

    private static Path scratchRoot(ToolContext ctx) {
        return ctx.scratchDir() == null ? null : ctx.scratchDir().toAbsolutePath().normalize();
    }

    private static Path resolveIn(Path rootDir, String path) {
        Path root = rootDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes the work dir: " + path);
        }
        // resolve symlinks on the deepest existing ancestor so a link inside the work dir cannot point outside it
        try {
            Path realRoot = root.toRealPath();
            Path existing = resolved;
            while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null && !existing.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException("Path escapes the work dir: " + path);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve path: " + path, e);
        }
        return resolved;
    }

    private HarnessTool readFile() {
        return new SimpleTool("read_file",
                "Read a text file from the chat work dir. Optional 1-based offset and line limit.",
                SimpleTool.schema(
                        "path", "File path relative to the work dir",
                        "offset:integer?", "First line to return (1-based)",
                        "limit:integer?", "Maximum number of lines"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.read(ctx, in, readers, maxReadBytes);
            Path p = jail(ctx, in.path("path").asText());
            if (Files.isDirectory(p)) throw new IllegalArgumentException("Not a file: " + in.path("path").asText());
            String ext = extension(p);
            DocumentReader reader = readers == null ? null : readers.get(ext);
            if (reader != null) {
                if (Files.size(p) > MAX_DOCUMENT_BYTES) {
                    throw new IllegalArgumentException("File too large (" + Files.size(p) + " bytes)");
                }
                return formatRead(DocumentReaders.extractLines(reader, p), in, maxReadBytes);
            }
            checkNotBinary(ext);
            return readText(() -> Files.newInputStream(p), in, maxReadBytes);
        });
    }

    /** A binary format with no registered reader is refused, not decoded as garbage. */
    static void checkNotBinary(String ext) {
        if (BINARY_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("No reader for ." + ext + "; add the "
                    + (ext.equals("pdf") ? "jllm-pdf" : ext.startsWith("doc") ? "jllm-docs" : "matching reader")
                    + " module");
        }
    }

    /** The {@code read_file} output of a text file, streamed: only the requested lines are kept in memory. */
    static String readText(TextEdits.Source source, JsonNode in, long maxReadBytes) throws IOException {
        long from = Math.max(1, in.path("offset").asLong(1)) - 1;
        long limit = in.has("limit") ? Math.max(0, in.path("limit").asLong()) : Long.MAX_VALUE;
        TextEdits.Window w = TextEdits.readWindow(source, from, (int) Math.min(limit, MAX_READ_LINES), maxReadBytes);
        long requestedTo = limit == Long.MAX_VALUE ? Long.MAX_VALUE : from + limit;
        long to = w.total() < 0 ? requestedTo : Math.min(w.total(), requestedTo);
        return formatWindow(w.lines(), from, to, w.total(), maxReadBytes);
    }

    /** The {@code read_file} output for all the lines of a file: numbered, offset/limit applied, capped. */
    static String formatRead(List<String> lines, JsonNode in, long maxReadBytes) {
        int from = (int) Math.min(lines.size(), Math.max(1, in.path("offset").asLong(1)) - 1);
        long to = in.has("limit") ? Math.min(lines.size(), from + Math.max(0, in.path("limit").asLong())) : lines.size();
        int cap = (int) Math.min(to, from + MAX_READ_LINES);
        return formatWindow(lines.subList(from, Math.max(from, cap)), from, to, lines.size(), maxReadBytes);
    }

    /**
     * Numbers the lines of {@code window} (line {@code from + 1} onwards) up to line {@code to}, at most
     * {@link #MAX_READ_LINES} lines and {@code min(MAX_READ_OUTPUT_BYTES, maxReadBytes)} characters (a single longer
     * line is cut at {@code maxReadBytes}), and tells how to continue. {@code total < 0}: unknown.
     */
    static String formatWindow(List<String> window, long from, long to, long total, long maxReadBytes) {
        long page = Math.min(MAX_READ_OUTPUT_BYTES, maxReadBytes);
        StringBuilder sb = new StringBuilder();
        long shown = from;
        for (int i = 0; i < window.size() && i < MAX_READ_LINES && from + i < to; i++) {
            String line = window.get(i);
            if (sb.length() + line.length() + 8 > page && i > 0) break;
            if (line.length() > maxReadBytes) {
                line = line.substring(0, (int) maxReadBytes) + " [line cut at " + maxReadBytes + " characters]";
            }
            sb.append(from + i + 1).append('\t').append(line).append('\n');
            shown = from + i + 1;
        }
        if (shown < to) {
            sb.append("[showing lines ").append(from + 1).append('-').append(shown)
                    .append(total < 0 ? "; more follow" : " of " + total)
                    .append("; continue with offset=").append(shown + 1).append("]\n");
        }
        return sb.toString();
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private HarnessTool writeFile() {
        return new SimpleTool("write_file",
                "Create or overwrite a file in the chat work dir. With append: true, add the content to the end of "
                        + "the file instead (creating it if missing): write a large file in several parts.",
                SimpleTool.schema(
                        "path", "File path relative to the work dir",
                        "content", "Full file content, or the part to append",
                        "append:boolean?", "Append to the file instead of overwriting it"),
                GROUP, ToolKind.FILE_EDIT, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.write(ctx, in, checkpoints, quota);
            String shown = in.path("path").asText();
            Path p = jailWrite(ctx, shown);
            String content = in.path("content").asText();
            try (PathLocks.Held ignored = PathLocks.lock(p)) {
                if (Files.isDirectory(p)) throw new IllegalArgumentException("Is a directory: " + shown);
                if (in.path("append").asBoolean(false)) {
                    boolean exists = Files.exists(p);
                    byte[] part = exists
                            ? TextEdits.TextFile.probe(readHead(p, TextEdits.EOL_PROBE_BYTES)).encodeAppend(content)
                            : content.getBytes(StandardCharsets.UTF_8); // keep the file's line endings and charset
                    StorageQuota.Reservation r = quota.reserve(ctx.chatId(), p, 0, part.length);
                    try {
                        checkpoint(ctx, p);
                        if (p.getParent() != null) Files.createDirectories(p.getParent());
                        if (exists) Files.write(p, part, StandardOpenOption.APPEND);
                        else Files.write(p, part, StandardOpenOption.CREATE);
                    } catch (IOException | RuntimeException e) {
                        r.rollback();
                        throw e;
                    }
                    return "Appended " + content.length() + " chars to " + shown;
                }
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                StorageQuota.Reservation r = quota.reserve(ctx.chatId(), p, Files.exists(p) ? Files.size(p) : 0,
                        bytes.length);
                try {
                    checkpoint(ctx, p);
                    if (p.getParent() != null) Files.createDirectories(p.getParent());
                    AtomicFiles.write(p, bytes);
                } catch (IOException | RuntimeException e) {
                    r.rollback();
                    throw e;
                }
                return "Wrote " + content.length() + " chars to " + shown;
            }
        }, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.writePreview(ctx, in);
            Path p = jailWrite(ctx, in.path("path").asText());
            String old = "";
            try {
                if (Files.isRegularFile(p) && Files.size(p) <= maxReadBytes && !in.path("append").asBoolean(false)) {
                    old = TextEdits.TextFile.read(p).text();
                }
            } catch (IOException | RuntimeException ignored) {
                // preview only
            }
            return "--- " + in.path("path").asText() + "\n" + diff(old, in.path("content").asText());
        });
    }

    private HarnessTool editFile() {
        var schema = SimpleTool.schema(
                "path", "File path relative to the work dir",
                "old_string?", "Text to replace (single edit)",
                "new_string?", "Replacement text (single edit)",
                "replace_all:boolean?", "Replace every occurrence (single edit)");
        var edits = schema.with("properties").putObject("edits");
        edits.put("type", "array");
        edits.put("description", "Several edits, each matched against the original file; all or none are applied. "
                + "Use instead of old_string/new_string.");
        var item = edits.putObject("items");
        item.put("type", "object");
        var props = item.putObject("properties");
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        props.putObject("replace_all").put("type", "boolean");
        item.putArray("required").add("old_string").add("new_string");
        return new SimpleTool("edit_file",
                "Replace text in a file. old_string must occur exactly once unless replace_all is true; small "
                        + "whitespace and quote differences are tolerated. Pass edits to change several places at once.",
                schema, GROUP, ToolKind.FILE_EDIT, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.edit(ctx, in, checkpoints, quota, maxReadBytes);
            String shown = in.path("path").asText();
            Path p = jailWrite(ctx, shown);
            List<TextEdits.Edit> requested = parseEdits(in);
            try (PathLocks.Held ignored = PathLocks.lock(p)) { // read, change and write as one step
                if (Files.isDirectory(p)) throw new IllegalArgumentException("Is a directory: " + shown);
                long size = Files.size(p);
                checkEditSize(shown, size, maxReadBytes);
                TextEdits.TextFile file = TextEdits.TextFile.read(p);
                TextEdits.Applied applied = TextEdits.apply(file.text(), requested);
                byte[] out = file.encode(applied);
                StorageQuota.Reservation r = quota.reserve(ctx.chatId(), p, size, out.length);
                try {
                    checkpoint(ctx, p);
                    AtomicFiles.write(p, out);
                } catch (IOException | RuntimeException e) {
                    r.rollback();
                    throw e;
                }
                return "Edited " + shown + " (" + applied.replacements() + " replacement(s))\n" + applied.diff();
            }
        }, (ctx, in) -> {
            StringBuilder sb = new StringBuilder("--- " + in.path("path").asText() + "\n");
            for (TextEdits.Edit e : parseEdits(in)) sb.append(diff(e.oldString(), e.newString()));
            return sb.toString();
        });
    }

    /** Edits load the whole file: above {@code files.maxReadBytes} they are refused. */
    static void checkEditSize(String shown, long size, long maxReadBytes) {
        if (size > maxReadBytes) {
            throw new IllegalArgumentException("File too large to edit (" + size + " bytes, the limit is " + maxReadBytes
                    + ", files.maxReadBytes): " + shown + "; change it with a shell command (e.g. sed) instead");
        }
    }

    /** The first {@code max} bytes of a file. */
    static byte[] readHead(Path p, int max) throws IOException {
        try (var s = Files.newInputStream(p)) {
            return s.readNBytes(max);
        }
    }

    static List<TextEdits.Edit> parseEdits(JsonNode in) {
        List<TextEdits.Edit> edits = new ArrayList<>();
        if (in.hasNonNull("old_string")) {
            if (!in.hasNonNull("new_string")) {
                throw new IllegalArgumentException("new_string is missing (use \"\" to delete the text)");
            }
            edits.add(new TextEdits.Edit(in.path("old_string").asText(), in.path("new_string").asText(),
                    in.path("replace_all").asBoolean(false)));
        }
        if (in.has("edits") && in.get("edits").isArray()) {
            int i = 0;
            for (JsonNode e : in.get("edits")) {
                i++;
                if (!e.hasNonNull("new_string")) {
                    throw new IllegalArgumentException("edit " + i + ": new_string is missing (use \"\" to delete the text)");
                }
                edits.add(new TextEdits.Edit(e.path("old_string").asText(""), e.path("new_string").asText(),
                        e.path("replace_all").asBoolean(false)));
            }
        }
        if (edits.isEmpty()) throw new IllegalArgumentException("Give old_string and new_string, or edits");
        return edits;
    }

    /** Java's glob requires a directory for a leading "**&#47;", so "**&#47;*.java" would miss top-level files; accept both. */
    static PathMatcher globMatcher(String pattern) {
        PathMatcher full = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        if (!pattern.startsWith("**/")) return full;
        PathMatcher topLevel = FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3));
        return p -> full.matches(p) || topLevel.matches(p);
    }

    /**
     * A glob matches a file relative to the searched {@code path} ({@code base}); a pattern written relative to the
     * work dir ({@code root}) is accepted too.
     */
    private static boolean globMatches(PathMatcher matcher, Path base, Path root, Path f) {
        if (base != null && f.startsWith(base) && matcher.matches(base.relativize(f))) return true;
        return f.startsWith(root) && matcher.matches(root.relativize(f));
    }

    /** Real path of the jail root {@code start} was resolved in: the scratch dir when it is there, else the work dir. */
    private static Path realJailRoot(ToolContext ctx, Path start) throws IOException {
        Path work = ctx.workDir().toAbsolutePath().normalize();
        Path scratch = scratchRoot(ctx);
        Path root = !start.startsWith(work) && scratch != null && start.startsWith(scratch) ? scratch : work;
        return root.toRealPath();
    }

    /** Whether a walked entry really is inside the jail: a symlink or junction to elsewhere is not followed. */
    private static boolean insideReal(Path p, Path realRoot) {
        try {
            return p.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private HarnessTool ls() {
        return new SimpleTool("ls",
                "List one directory (not recursive): directories first, then files, with type and size in bytes.",
                SimpleTool.schema("path?", "Directory relative to the work dir (default: the work dir)"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (ctx.files() != null) return "ls is not available on a remote work dir; use glob";
            Path dir = in.hasNonNull("path") && !in.path("path").asText().isBlank()
                    ? jail(ctx, in.path("path").asText()) : ctx.workDir().toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) return "Not a directory: " + in.path("path").asText();
            Path realRoot = realJailRoot(ctx, dir);
            List<String> dirs = new ArrayList<>();
            List<String> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s.filter(e -> insideReal(e, realRoot)).sorted()::iterator) {
                    String name = p.getFileName().toString();
                    if (Files.isDirectory(p)) dirs.add("d " + name + "/");
                    else {
                        long size;
                        try {
                            size = Files.size(p);
                        } catch (IOException e) {
                            size = -1;
                        }
                        files.add("f " + name + " " + size);
                    }
                }
            }
            List<String> all = new ArrayList<>(dirs);
            all.addAll(files);
            if (all.isEmpty()) return "(empty)";
            if (all.size() > MAX_RESULTS) {
                int more = all.size() - MAX_RESULTS;
                all = new ArrayList<>(all.subList(0, MAX_RESULTS));
                all.add("... " + more + " more entries");
            }
            return String.join("\n", all);
        });
    }

    private HarnessTool glob() {
        return new SimpleTool("glob",
                "List files in the work dir matching a glob pattern (e.g. **/*.java).",
                SimpleTool.schema("pattern", "Glob pattern relative to the work dir"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.glob(ctx, in);
            Path root = ctx.workDir().toAbsolutePath().normalize();
            Path realRoot = root.toRealPath();
            PathMatcher matcher = globMatcher(in.path("pattern").asText());
            try (Stream<Path> s = Files.walk(root)) {
                return s.filter(Files::isRegularFile)
                        .filter(f -> insideReal(f, realRoot))
                        .map(root::relativize)
                        .filter(matcher::matches)
                        .map(p -> p.toString().replace('\\', '/'))
                        .sorted()
                        .limit(MAX_RESULTS)
                        .collect(Collectors.joining("\n"));
            }
        });
    }

    private HarnessTool grep() {
        return new SimpleTool("grep",
                "Search file contents in the work dir with a regex. Returns path:line:text.",
                SimpleTool.schema(
                        "pattern", "Java regular expression",
                        "path?", "Subdirectory or file relative to the work dir (default: whole dir)",
                        "glob?", "Only search files matching this glob (relative to path)"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.grep(ctx, in);
            Path root = ctx.workDir().toAbsolutePath().normalize();
            Path start = in.hasNonNull("path") ? jail(ctx, in.path("path").asText()) : root;
            Path realRoot = realJailRoot(ctx, start);
            Pattern pattern = Pattern.compile(in.path("pattern").asText());
            PathMatcher matcher = in.hasNonNull("glob") ? globMatcher(in.path("glob").asText()) : null;
            Path globBase = Files.isDirectory(start) ? start : start.getParent();
            List<String> hits = new ArrayList<>();
            try (Stream<Path> s = Files.walk(start)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    if (!insideReal(f, realRoot)) continue; // a link to a file outside the jail
                    Path rel = root.relativize(f);
                    if (matcher != null && !globMatches(matcher, globBase, root, f)) continue;
                    List<String> lines;
                    try {
                        if (Files.size(f) > maxReadBytes) continue;
                        lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        continue; // binary / unreadable
                    }
                    for (int i = 0; i < lines.size() && hits.size() < MAX_RESULTS; i++) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            hits.add(rel.toString().replace('\\', '/') + ":" + (i + 1) + ":" + lines.get(i));
                        }
                    }
                    if (hits.size() >= MAX_RESULTS) break;
                }
            }
            return String.join("\n", hits);
        });
    }

    private static final int CODE_MAX_FILES = 80;
    private static final int CODE_MAX_PER_FILE = 60;
    private static final int CODE_MAX_CHARS = 40_000;
    private static final java.util.Set<String> CODE_SKIP_DIRS = java.util.Set.of(
            ".git", "target", "build", "node_modules", ".idea", ".gradle", "dist", "out", "__pycache__", "vendor");
    /**
     * Language-neutral guess at a definition: a shallow line that opens a block ({, :, =>) and does not start with a
     * control-flow word. Deliberately not a list of languages: pass pattern when it misses.
     */
    private static final Pattern BLOCK_OPENER = Pattern.compile(
            "^(?!\\s*(?:if|else|elif|for|foreach|while|do|switch|case|default|try|catch|finally|except|with|return|"
                    + "when|match|synchronized|lock|using|unless|until|loop)\\b)\\s{0,8}[^\\s/*#@].*(?:\\{|:|=>)\\s*$");

    private HarnessTool grepCode() {
        return new SimpleTool("grep_code",
                "Find what files DEFINE, grouped per file with its line count and numbered lines: use it to learn what "
                        + "a module or directory contains instead of reading the first lines of each file. Without "
                        + "pattern it lists shallow lines that open a block (types, functions, methods) in any "
                        + "language; when that is too thin or noisy for the language or question, pass a pattern for "
                        + "the definitions you care about. Skips build, vendor and binary files.",
                SimpleTool.schema(
                        "path?", "File or directory relative to the work dir (default: the work dir)",
                        "glob?", "Only files matching this glob (relative to path), e.g. src/main/**/*.go",
                        "pattern?", "Java regex for the definition lines (default: language-neutral block openers)"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (ctx.files() != null) return "grep_code is not available on a remote work dir; use glob, grep and read_file";
            Path root = ctx.workDir().toAbsolutePath().normalize();
            Path start = in.hasNonNull("path") ? jail(ctx, in.path("path").asText()) : root;
            Path realRoot = realJailRoot(ctx, start);
            PathMatcher matcher = in.hasNonNull("glob") ? globMatcher(in.path("glob").asText()) : null;
            Path globBase = Files.isDirectory(start) ? start : start.getParent();
            Pattern pattern = in.hasNonNull("pattern") ? Pattern.compile(in.path("pattern").asText()) : null;
            StringBuilder sb = new StringBuilder();
            int files = 0;
            boolean more = false;
            try (Stream<Path> s = Files.walk(start)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                    if (!insideReal(f, realRoot)) continue; // a link to a file outside the jail
                    Path rel = root.relativize(f);
                    boolean skipped = false;
                    for (Path seg : rel) if (CODE_SKIP_DIRS.contains(seg.toString())) skipped = true;
                    if (skipped || BINARY_EXTENSIONS.contains(extension(f))) continue;
                    if (matcher != null && !globMatches(matcher, globBase, root, f)) continue;
                    List<String> lines;
                    try {
                        if (Files.size(f) > maxReadBytes) continue;
                        lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        continue; // binary / unreadable
                    }
                    StringBuilder one = new StringBuilder("## " + rel.toString().replace('\\', '/') + " ("
                            + lines.size() + " lines)\n");
                    int hits = 0;
                    for (int i = 0; i < lines.size(); i++) {
                        String l = lines.get(i);
                        if (!(pattern != null ? pattern.matcher(l).find() : BLOCK_OPENER.matcher(l).matches())) continue;
                        if (++hits > CODE_MAX_PER_FILE) continue;
                        String t = l.strip();
                        one.append("  L").append(i + 1).append(' ')
                                .append(t.length() > 140 ? t.substring(0, 140) + "…" : t).append('\n');
                    }
                    if (hits > CODE_MAX_PER_FILE) one.append("  ... ").append(hits - CODE_MAX_PER_FILE).append(" more\n");
                    if (files >= CODE_MAX_FILES || (sb.length() + one.length() > CODE_MAX_CHARS && files > 0)) {
                        more = true;
                        break;
                    }
                    sb.append(one);
                    files++;
                }
            }
            if (more) sb.append("[output limited: narrow path or glob to see the remaining files]\n");
            return sb.isEmpty() ? "No files" : sb.toString();
        });
    }

    /** Minimal line diff: removed lines prefixed "-", added "+". */
    static String diff(String oldText, String newText) {
        StringBuilder sb = new StringBuilder();
        for (String l : oldText.split("\n", -1)) {
            if (!oldText.isEmpty()) sb.append("- ").append(l).append('\n');
        }
        for (String l : newText.split("\n", -1)) {
            if (!newText.isEmpty()) sb.append("+ ").append(l).append('\n');
        }
        return sb.toString();
    }
}

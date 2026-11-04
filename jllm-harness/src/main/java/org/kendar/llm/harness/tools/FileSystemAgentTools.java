package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** File tools scoped to the per-chat work dir. Never exposed via MCP/REST. */
public class FileSystemAgentTools implements HarnessToolSet {

    private static final String GROUP = "Files";
    private static final int MAX_RESULTS = 200;
    private static final long MAX_READ_BYTES = 1_000_000;
    private static final long MAX_DOCUMENT_BYTES = 50_000_000;
    static final int MAX_READ_LINES = 2000;
    static final int MAX_READ_OUTPUT_BYTES = 50 * 1024;
    private static final java.util.Set<String> BINARY_EXTENSIONS = java.util.Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "jar", "class", "exe", "dll", "png", "jpg",
            "jpeg", "gif", "bmp", "ico", "mp3", "mp4", "7z", "gz", "tar");

    private final DocumentReaders readers;
    private final Checkpoints checkpoints;

    public FileSystemAgentTools() {
        this(null, null);
    }

    public FileSystemAgentTools(DocumentReaders readers) {
        this(readers, null);
    }

    public FileSystemAgentTools(DocumentReaders readers, Checkpoints checkpoints) {
        this.readers = readers;
        this.checkpoints = checkpoints;
    }

    private void checkpoint(ToolContext ctx, Path p) {
        if (checkpoints != null) checkpoints.record(ctx, p);
    }

    @Override
    public List<HarnessTool> tools() {
        return List.of(readFile(), writeFile(), editFile(), glob(), grep());
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
        if (!ctx.readOnlyWork()) return jail(ctx, path);
        Path scratch = scratchRoot(ctx);
        if (Path.of(path).isAbsolute() && !Path.of(path).normalize().startsWith(scratch)) {
            throw new IllegalArgumentException("Plan mode: the working directory is read-only; write under "
                    + scratch + " instead");
        }
        return resolveIn(scratch, path);
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
            if (ctx.files() != null) return RemoteFileTools.read(ctx, in, readers);
            Path p = jail(ctx, in.path("path").asText());
            return formatRead(readLines(p), in);
        });
    }

    /** The {@code read_file} output for the lines of a file: numbered, offset/limit applied, capped. */
    static String formatRead(List<String> lines, JsonNode in) {
        int from = Math.max(1, in.path("offset").asInt(1)) - 1;
        int to = in.has("limit") ? Math.min(lines.size(), from + in.path("limit").asInt()) : lines.size();
        int cap = Math.min(to, from + MAX_READ_LINES);
        StringBuilder sb = new StringBuilder();
        int shown = from;
        for (int i = from; i < cap; i++) {
            String line = lines.get(i);
            if (sb.length() + line.length() + 8 > MAX_READ_OUTPUT_BYTES && i > from) break;
            sb.append(i + 1).append('\t').append(line).append('\n');
            shown = i + 1;
        }
        if (shown < to) {
            sb.append("[showing lines ").append(from + 1).append('-').append(shown).append(" of ")
                    .append(lines.size()).append("; continue with offset=").append(shown + 1).append("]\n");
        }
        return sb.toString();
    }

    /** Reads a text file as lines; a binary format with no registered reader is refused, not decoded as garbage. */
    private List<String> readLines(Path p) throws IOException {
        String ext = extension(p);
        DocumentReader reader = readers == null ? null : readers.get(ext);
        if (reader != null) {
            if (Files.size(p) > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("File too large (" + Files.size(p) + " bytes)");
            }
            return DocumentReaders.extractLines(reader, p);
        }
        if (BINARY_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("No reader for ." + ext + "; add the "
                    + (ext.equals("pdf") ? "jllm-pdf" : ext.startsWith("doc") ? "jllm-docs" : "matching reader")
                    + " module");
        }
        if (Files.size(p) > MAX_READ_BYTES) {
            throw new IllegalArgumentException("File too large (" + Files.size(p) + " bytes); use grep or a line range");
        }
        return Files.readAllLines(p, StandardCharsets.UTF_8);
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
            if (ctx.files() != null) return RemoteFileTools.write(ctx, in, checkpoints);
            Path p = jailWrite(ctx, in.path("path").asText());
            checkpoint(ctx, p);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            String content = in.path("content").asText();
            if (in.path("append").asBoolean(false)) {
                if (Files.exists(p)) { // keep the file's line endings
                    TextEdits.TextFile existing = TextEdits.TextFile.read(p);
                    byte[] part = new TextEdits.TextFile("", false, existing.eol())
                            .encode(content.replace("\r\n", "\n"));
                    Files.write(p, part, StandardOpenOption.APPEND);
                } else {
                    Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                }
                return "Appended " + content.length() + " chars to " + in.path("path").asText();
            }
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return "Wrote " + content.length() + " chars to " + in.path("path").asText();
        }, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.writePreview(ctx, in);
            Path p = jailWrite(ctx, in.path("path").asText());
            String old = "";
            try {
                if (Files.exists(p) && !in.path("append").asBoolean(false)) old = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
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
            if (ctx.files() != null) return RemoteFileTools.edit(ctx, in, checkpoints);
            Path p = jailWrite(ctx, in.path("path").asText());
            TextEdits.TextFile file = TextEdits.TextFile.read(p);
            TextEdits.Applied applied = TextEdits.apply(file.text(), parseEdits(in));
            checkpoint(ctx, p);
            file.write(p, applied.text());
            return "Edited " + in.path("path").asText() + " (" + applied.replacements() + " replacement(s))\n"
                    + applied.diff();
        }, (ctx, in) -> {
            StringBuilder sb = new StringBuilder("--- " + in.path("path").asText() + "\n");
            for (TextEdits.Edit e : parseEdits(in)) sb.append(diff(e.oldString(), e.newString()));
            return sb.toString();
        });
    }

    static List<TextEdits.Edit> parseEdits(JsonNode in) {
        List<TextEdits.Edit> edits = new ArrayList<>();
        if (in.hasNonNull("old_string")) {
            edits.add(new TextEdits.Edit(in.path("old_string").asText(), in.path("new_string").asText(""),
                    in.path("replace_all").asBoolean(false)));
        }
        if (in.has("edits") && in.get("edits").isArray()) {
            for (JsonNode e : in.get("edits")) {
                edits.add(new TextEdits.Edit(e.path("old_string").asText(""), e.path("new_string").asText(""),
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

    private HarnessTool glob() {
        return new SimpleTool("glob",
                "List files in the work dir matching a glob pattern (e.g. **/*.java).",
                SimpleTool.schema("pattern", "Glob pattern relative to the work dir"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.glob(ctx, in);
            Path root = ctx.workDir().toAbsolutePath().normalize();
            PathMatcher matcher = globMatcher(in.path("pattern").asText());
            try (Stream<Path> s = Files.walk(root)) {
                return s.filter(Files::isRegularFile)
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
                        "glob?", "Only search files matching this glob"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (ctx.files() != null) return RemoteFileTools.grep(ctx, in);
            Path root = ctx.workDir().toAbsolutePath().normalize();
            Path start = in.hasNonNull("path") ? jail(ctx, in.path("path").asText()) : root;
            Pattern pattern = Pattern.compile(in.path("pattern").asText());
            PathMatcher matcher = in.hasNonNull("glob") ? globMatcher(in.path("glob").asText()) : null;
            List<String> hits = new ArrayList<>();
            try (Stream<Path> s = Files.walk(start)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    Path rel = root.relativize(f);
                    if (matcher != null && !matcher.matches(rel)) continue;
                    List<String> lines;
                    try {
                        if (Files.size(f) > MAX_READ_BYTES) continue;
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

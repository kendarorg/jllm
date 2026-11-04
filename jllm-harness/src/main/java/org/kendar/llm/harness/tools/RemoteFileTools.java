package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.sandbox.FileBackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The file tools over a {@link FileBackend} (the exec daemon of a container). Same results, limits and messages as the
 * host implementation in {@link FileSystemAgentTools}; the daemon only moves raw bytes and the formatting, size limits
 * and edit logic stay here. Checkpoints are kept inside the container, through the same backend.
 */
final class RemoteFileTools {

    private static final long MAX_READ_BYTES = 1_000_000;
    private static final long MAX_DOCUMENT_BYTES = 50_000_000;
    private static final int MAX_RESULTS = 200;
    private static final int EOL_PROBE_BYTES = 64 * 1024;
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "jar", "class", "exe", "dll", "png", "jpg",
            "jpeg", "gif", "bmp", "ico", "mp3", "mp4", "7z", "gz", "tar");

    private RemoteFileTools() {
    }

    static String read(ToolContext ctx, JsonNode in, DocumentReaders readers) throws IOException {
        FileBackend fb = ctx.files();
        FileBackend.Target t = fb.readTarget(in.path("path").asText());
        FileBackend.Stat st = fb.stat(t);
        if (!st.exists()) throw new NoSuchFileException(in.path("path").asText());
        if (!st.isFile()) throw new IllegalArgumentException("Not a file: " + in.path("path").asText());
        String ext = extension(in.path("path").asText());
        DocumentReader reader = readers == null ? null : readers.get(ext);
        List<String> lines;
        if (reader != null) {
            if (st.size() > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("File too large (" + st.size() + " bytes)");
            }
            Path tmp = Files.createTempFile("jllm-doc-", "." + ext);
            try {
                Files.write(tmp, fb.read(t, 0, -1));
                lines = DocumentReaders.extractLines(reader, tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } else {
            if (BINARY_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("No reader for ." + ext + "; add the "
                        + (ext.equals("pdf") ? "jllm-pdf" : ext.startsWith("doc") ? "jllm-docs" : "matching reader")
                        + " module");
            }
            if (st.size() > MAX_READ_BYTES) {
                throw new IllegalArgumentException("File too large (" + st.size()
                        + " bytes); use grep or a line range");
            }
            lines = splitLines(new String(fb.read(t, 0, -1), StandardCharsets.UTF_8));
        }
        return FileSystemAgentTools.formatRead(lines, in);
    }

    /** Like {@link Files#readAllLines}: a trailing line end does not add an empty line. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
        }
        if (start < n) out.add(text.substring(start));
        return out;
    }

    static String write(ToolContext ctx, JsonNode in, Checkpoints checkpoints) throws IOException {
        FileBackend fb = ctx.files();
        String shown = in.path("path").asText();
        FileBackend.Target t = fb.writeTarget(shown, ctx.readOnlyWork());
        if (checkpoints != null) checkpoints.record(ctx, t);
        String content = in.path("content").asText();
        if (in.path("append").asBoolean(false)) {
            byte[] part;
            if (fb.stat(t).exists()) { // keep the file's line endings
                byte[] head = fb.read(t, 0, EOL_PROBE_BYTES);
                TextEdits.TextFile existing = TextEdits.TextFile.decode(head);
                part = new TextEdits.TextFile("", false, existing.eol()).encode(content.replace("\r\n", "\n"));
            } else {
                part = content.getBytes(StandardCharsets.UTF_8);
            }
            fb.write(t, part, true);
            return "Appended " + content.length() + " chars to " + shown;
        }
        fb.write(t, content.getBytes(StandardCharsets.UTF_8), false);
        return "Wrote " + content.length() + " chars to " + shown;
    }

    static String writePreview(ToolContext ctx, JsonNode in) {
        FileBackend fb = ctx.files();
        String old = "";
        try {
            FileBackend.Target t = fb.writeTarget(in.path("path").asText(), ctx.readOnlyWork());
            if (!in.path("append").asBoolean(false) && fb.stat(t).isFile()) {
                old = new String(fb.read(t, 0, MAX_READ_BYTES), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // preview only
        }
        return "--- " + in.path("path").asText() + "\n" + FileSystemAgentTools.diff(old, in.path("content").asText());
    }

    static String edit(ToolContext ctx, JsonNode in, Checkpoints checkpoints) throws IOException {
        FileBackend fb = ctx.files();
        FileBackend.Target t = fb.writeTarget(in.path("path").asText(), ctx.readOnlyWork());
        TextEdits.TextFile file = TextEdits.TextFile.decode(fb.read(t, 0, -1));
        TextEdits.Applied applied = TextEdits.apply(file.text(), FileSystemAgentTools.parseEdits(in));
        if (checkpoints != null) checkpoints.record(ctx, t);
        fb.write(t, file.encode(applied.text()), false);
        return "Edited " + in.path("path").asText() + " (" + applied.replacements() + " replacement(s))\n"
                + applied.diff();
    }

    static String glob(ToolContext ctx, JsonNode in) throws IOException {
        return ctx.files().glob(in.path("pattern").asText()).stream().limit(MAX_RESULTS)
                .collect(Collectors.joining("\n"));
    }

    static String grep(ToolContext ctx, JsonNode in) throws IOException {
        String path = in.hasNonNull("path") ? in.path("path").asText() : null;
        String glob = in.hasNonNull("glob") ? in.path("glob").asText() : null;
        List<String> hits = ctx.files().grep(in.path("pattern").asText(), path, glob);
        return hits.stream().limit(MAX_RESULTS).collect(Collectors.joining("\n"));
    }

    private static String extension(String path) {
        String n = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

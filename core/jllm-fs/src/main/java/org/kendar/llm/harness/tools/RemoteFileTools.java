package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.sandbox.FileBackend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The file tools over a {@link FileBackend} (the exec daemon of a container). Same results, limits and messages as the
 * host implementation in {@link FileSystemAgentTools}; the daemon only moves raw bytes and the formatting, size limits
 * and edit logic stay here. Checkpoints are kept inside the container, through the same backend.
 */
final class RemoteFileTools {

    private static final long MAX_DOCUMENT_BYTES = 50_000_000;
    private static final int MAX_RESULTS = 200;
    /** Bytes fetched per call when streaming a file for {@code read_file}. */
    private static final int READ_CHUNK = 256 * 1024;

    private RemoteFileTools() {
    }

    static String read(ToolContext ctx, JsonNode in, DocumentReaders readers, long maxReadBytes) throws IOException {
        FileBackend fb = ctx.files();
        FileBackend.Target t = fb.readTarget(in.path("path").asText());
        FileBackend.Stat st = fb.stat(t);
        if (!st.exists()) throw new NoSuchFileException(in.path("path").asText());
        if (!st.isFile()) throw new IllegalArgumentException("Not a file: " + in.path("path").asText());
        String ext = extension(in.path("path").asText());
        DocumentReader reader = readers == null ? null : readers.get(ext);
        if (reader != null) {
            if (st.size() > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("File too large (" + st.size() + " bytes)");
            }
            Path tmp = Files.createTempFile("jllm-doc-", "." + ext);
            try {
                Files.write(tmp, fb.read(t, 0, -1));
                return FileSystemAgentTools.formatRead(DocumentReaders.extractLines(reader, tmp), in, maxReadBytes);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        FileSystemAgentTools.checkNotBinary(ext);
        return FileSystemAgentTools.readText(() -> chunked(fb, t), in, maxReadBytes);
    }

    /** The file as a stream fetched in {@link #READ_CHUNK} pieces, so only the lines asked for are kept. */
    private static InputStream chunked(FileBackend fb, FileBackend.Target t) {
        return new InputStream() {
            private long pos;
            private byte[] buf = new byte[0];
            private int i;
            private boolean eof;

            private boolean fill() throws IOException {
                if (i < buf.length) return true;
                if (eof) return false;
                buf = fb.read(t, pos, READ_CHUNK);
                i = 0;
                pos += buf.length;
                if (buf.length < READ_CHUNK) eof = true;
                return buf.length > 0;
            }

            @Override
            public int read() throws IOException {
                return fill() ? buf[i++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (!fill()) return -1;
                int n = Math.min(len, buf.length - i);
                System.arraycopy(buf, i, b, off, n);
                i += n;
                return n;
            }
        };
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

    /** Where a tool write goes; checkpoint data is refused. */
    private static FileBackend.Target writeTarget(ToolContext ctx, String shown) {
        FileBackend fb = ctx.files();
        FileBackend.Target t = fb.writeTarget(shown, ctx.readOnlyWork());
        if (Checkpoints.isCheckpointPath(fb, t)) {
            throw new IllegalArgumentException("Checkpoint data cannot be written: " + shown);
        }
        return t;
    }

    private static PathLocks.Held lock(ToolContext ctx, FileBackend.Target t) {
        return PathLocks.lockKey("remote:" + ctx.chatId() + ":" + t.root() + ":" + t.path());
    }

    private static void checkpoint(ToolContext ctx, FileBackend.Target t, Checkpoints checkpoints, StorageQuota quota) {
        if (checkpoints != null) checkpoints.record(ctx, t, quota);
    }

    static String write(ToolContext ctx, JsonNode in, Checkpoints checkpoints, StorageQuota quota)
            throws IOException {
        FileBackend fb = ctx.files();
        String shown = in.path("path").asText();
        FileBackend.Target t = writeTarget(ctx, shown);
        String content = in.path("content").asText();
        try (PathLocks.Held ignored = lock(ctx, t)) {
            FileBackend.Stat old = fb.stat(t);
            if (old.isDir()) throw new IllegalArgumentException("Is a directory: " + shown);
            if (in.path("append").asBoolean(false)) {
                byte[] part = old.exists() // keep the file's line endings and charset
                        ? TextEdits.TextFile.probe(fb.read(t, 0, TextEdits.EOL_PROBE_BYTES)).encodeAppend(content)
                        : content.getBytes(StandardCharsets.UTF_8);
                StorageQuota.Reservation r = quota.reserveRemote(ctx.chatId(), part.length);
                try {
                    checkpoint(ctx, t, checkpoints, quota);
                    fb.write(t, part, true);
                } catch (IOException | RuntimeException e) {
                    r.rollback();
                    throw e;
                }
                return "Appended " + content.length() + " chars to " + shown;
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            StorageQuota.Reservation r = quota.reserveRemote(ctx.chatId(), bytes.length - (old.exists() ? old.size() : 0));
            try {
                checkpoint(ctx, t, checkpoints, quota);
                fb.write(t, bytes, false);
            } catch (IOException | RuntimeException e) {
                r.rollback();
                throw e;
            }
            return "Wrote " + content.length() + " chars to " + shown;
        }
    }

    static String writePreview(ToolContext ctx, JsonNode in) {
        FileBackend fb = ctx.files();
        String old = "";
        try {
            FileBackend.Target t = fb.writeTarget(in.path("path").asText(), ctx.readOnlyWork());
            if (!in.path("append").asBoolean(false) && fb.stat(t).isFile()) {
                old = TextEdits.TextFile.probe(fb.read(t, 0, FileSystemAgentTools.DEFAULT_MAX_READ_BYTES)).text();
            }
        } catch (IOException | RuntimeException ignored) {
            // preview only
        }
        return "--- " + in.path("path").asText() + "\n" + FileSystemAgentTools.diff(old, in.path("content").asText());
    }

    static String edit(ToolContext ctx, JsonNode in, Checkpoints checkpoints, StorageQuota quota, long maxReadBytes)
            throws IOException {
        FileBackend fb = ctx.files();
        String shown = in.path("path").asText();
        FileBackend.Target t = writeTarget(ctx, shown);
        List<TextEdits.Edit> edits = FileSystemAgentTools.parseEdits(in);
        try (PathLocks.Held ignored = lock(ctx, t)) { // read, change and write as one step
            FileBackend.Stat st = fb.stat(t);
            if (!st.exists()) throw new NoSuchFileException(shown);
            if (!st.isFile()) throw new IllegalArgumentException("Not a file: " + shown);
            FileSystemAgentTools.checkEditSize(shown, st.size(), maxReadBytes);
            TextEdits.TextFile file = TextEdits.TextFile.decode(fb.read(t, 0, -1));
            TextEdits.Applied applied = TextEdits.apply(file.text(), edits);
            byte[] out = file.encode(applied);
            StorageQuota.Reservation r = quota.reserveRemote(ctx.chatId(), out.length - st.size());
            try {
                checkpoint(ctx, t, checkpoints, quota);
                fb.write(t, out, false);
            } catch (IOException | RuntimeException e) {
                r.rollback();
                throw e;
            }
            return "Edited " + shown + " (" + applied.replacements() + " replacement(s))\n" + applied.diff();
        }
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

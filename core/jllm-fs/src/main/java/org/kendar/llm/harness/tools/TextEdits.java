package org.kendar.llm.harness.tools;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Text file reading/writing that preserves charset, BOM and line endings, plus lenient string replacement.
 * <p>
 * Charset: strict UTF-8 (with or without BOM); a file that is not valid UTF-8 is read as windows-1252 (ISO-8859-1 when
 * it uses bytes windows-1252 leaves undefined) and written back in the same charset. A file with NUL bytes is binary
 * and refused.
 */
public final class TextEdits {

    /** Bytes read from the start of a file to learn its line ending and charset before an append. */
    public static final int EOL_PROBE_BYTES = 64 * 1024;
    /** Bytes checked for NUL to tell a binary file from text when reading. */
    static final int BINARY_PROBE_BYTES = 8000;
    /** Characters scanned past the requested window to count the lines of the file. */
    static final long MAX_COUNT_SCAN = 64L * 1024 * 1024;

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Charset WINDOWS_1252 = windows1252();

    private TextEdits() {
    }

    private static Charset windows1252() {
        try {
            return Charset.forName("windows-1252");
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * A decoded text file, remembering how to write it back.
     *
     * @param raw     the content with its original line endings, without BOM
     * @param charset the charset it was decoded with (and is written back with)
     * @param bom     whether it starts with a UTF-8 BOM
     * @param eol     the dominant line ending, used for new lines that have no surrounding line
     */
    public record TextFile(String raw, Charset charset, boolean bom, String eol) {

        public static TextFile read(Path p) throws IOException {
            return decode(Files.readAllBytes(p));
        }

        /** Decodes a whole file. */
        public static TextFile decode(byte[] bytes) {
            return decode(bytes, false);
        }

        /** Decodes the first bytes of a file: a character cut at the end does not count as malformed. */
        public static TextFile probe(byte[] head) {
            return decode(head, true);
        }

        private static TextFile decode(byte[] bytes, boolean truncated) {
            for (byte b : bytes) {
                if (b == 0) throw new IllegalArgumentException("Binary file (contains NUL bytes); not changed");
            }
            boolean bom = startsWithBom(bytes);
            int off = bom ? 3 : 0;
            int len = bytes.length - off;
            if (truncated) len -= incompleteUtf8Tail(bytes, off, len);
            String s;
            Charset cs;
            try {
                s = strictDecoder(StandardCharsets.UTF_8).decode(ByteBuffer.wrap(bytes, off, len)).toString();
                cs = StandardCharsets.UTF_8;
            } catch (CharacterCodingException e) {
                cs = singleByteCharset(bytes);
                s = new String(bytes, cs); // a BOM-like prefix stays as text and round-trips
                bom = false;
            }
            int crlf = 0, lf = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    if (i > 0 && s.charAt(i - 1) == '\r') crlf++;
                    else lf++;
                }
            }
            return new TextFile(s, cs, bom, crlf > lf ? "\r\n" : "\n");
        }

        /** The content with every CRLF turned into LF: the text edits work on. */
        public String text() {
            return raw.replace("\r\n", "\n");
        }

        /** New content (LF line ends) in the file's charset, BOM and dominant line ending. */
        public byte[] encode(String lfText) {
            return withBom(encodeStrict("\n".equals(eol) ? lfText : lfText.replace("\n", eol)));
        }

        /** Text to add at the end of the file: the dominant line ending, the charset, no BOM. */
        public byte[] encodeAppend(String text) {
            String lf = text.replace("\r\n", "\n");
            return encodeStrict("\n".equals(eol) ? lf : lf.replace("\n", eol));
        }

        /**
         * The file with {@code applied} (computed on {@link #text()}) put in: untouched lines keep their own line
         * ending, new lines take the ending of the line they are inserted in.
         */
        public byte[] encode(Applied applied) {
            int[] map = new int[raw.length() + 1]; // LF-normalized offset -> raw offset
            int n = 0;
            for (int i = 0; i < raw.length(); i++) {
                map[n++] = i;
                if (raw.charAt(i) == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') i++;
            }
            map[n] = raw.length();
            StringBuilder out = new StringBuilder(raw.length() + 64);
            int pos = 0;
            for (Range r : applied.ranges()) {
                int s = map[r.start()];
                out.append(raw, pos, s);
                String eolHere = eolAt(s);
                out.append("\n".equals(eolHere) ? r.replacement() : r.replacement().replace("\n", eolHere));
                pos = map[r.end()];
            }
            out.append(raw, pos, raw.length());
            return withBom(encodeStrict(out.toString()));
        }

        /** Ending of the line at raw offset {@code at}; the previous line's when it is the last one; else the dominant. */
        private String eolAt(int at) {
            int nl = raw.indexOf('\n', at);
            if (nl < 0) nl = at > 0 ? raw.lastIndexOf('\n', at - 1) : -1;
            if (nl < 0) return eol;
            return nl > 0 && raw.charAt(nl - 1) == '\r' ? "\r\n" : "\n";
        }

        private byte[] encodeStrict(String s) {
            if (charset.equals(StandardCharsets.UTF_8)) return s.getBytes(StandardCharsets.UTF_8);
            try {
                ByteBuffer b = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));
                byte[] out = new byte[b.remaining()];
                b.get(out);
                return out;
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("The file is " + charset.name() + ", which cannot store some "
                        + "characters of the new text; use characters of that charset, or convert the file to UTF-8 "
                        + "first");
            }
        }

        private byte[] withBom(byte[] body) {
            if (!bom) return body;
            byte[] out = new byte[body.length + 3];
            System.arraycopy(UTF8_BOM, 0, out, 0, 3);
            System.arraycopy(body, 0, out, 3, body.length);
            return out;
        }
    }

    private static boolean startsWithBom(byte[] b) {
        return b.length >= 3 && b[0] == UTF8_BOM[0] && b[1] == UTF8_BOM[1] && b[2] == UTF8_BOM[2];
    }

    private static CharsetDecoder strictDecoder(Charset cs) {
        return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /** windows-1252 when every byte is defined there, else ISO-8859-1 (which maps every byte, so it round-trips). */
    private static Charset singleByteCharset(byte[] bytes) {
        try {
            strictDecoder(WINDOWS_1252).decode(ByteBuffer.wrap(bytes));
            return WINDOWS_1252;
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** Number of bytes at the end of {@code b[off, off+len)} that start a UTF-8 sequence cut short (0..3). */
    private static int incompleteUtf8Tail(byte[] b, int off, int len) {
        for (int back = 1; back <= Math.min(3, len); back++) {
            int c = b[off + len - back] & 0xFF;
            if ((c & 0xC0) == 0x80) continue; // continuation byte: look further back
            int need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
            return need > back ? back : 0;
        }
        return 0;
    }

    /** Opens the bytes to read again from the start. */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    /**
     * Some lines of a text file.
     *
     * @param lines the lines from the first requested one, each cut to {@code maxChars + 1} characters
     * @param total lines in the file; -1 when the file is too long to count them all
     */
    public record Window(List<String> lines, long total) {
    }

    /**
     * Streams a text file and keeps lines {@code from} (0-based) to {@code from + count}, at most {@code maxChars}
     * characters in all (the first line is always kept, cut to {@code maxChars + 1} characters so the caller sees it
     * was cut). Line ends are LF, CRLF or CR; a trailing line end does not add an empty line. UTF-8 is tried first and
     * windows-1252 used when the file is not UTF-8. A file with NUL bytes near the start is refused as binary.
     */
    public static Window readWindow(Source source, long from, int count, long maxChars) throws IOException {
        try {
            return scan(source, from, count, maxChars, true);
        } catch (CharacterCodingException e) {
            return scan(source, from, count, maxChars, false);
        }
    }

    private static Window scan(Source source, long from, int count, long maxChars, boolean utf8) throws IOException {
        try (InputStream raw = source.open(); BufferedInputStream in = new BufferedInputStream(raw, 65536)) {
            in.mark(BINARY_PROBE_BYTES + 1);
            byte[] head = in.readNBytes(BINARY_PROBE_BYTES);
            for (byte b : head) {
                if (b == 0) throw new IllegalArgumentException("Binary file (contains NUL bytes); not shown");
            }
            in.reset();
            if (startsWithBom(head)) in.skipNBytes(3);
            CharsetDecoder dec = utf8 ? strictDecoder(StandardCharsets.UTF_8)
                    : WINDOWS_1252.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            Reader r = new InputStreamReader(in, dec);
            List<String> lines = new ArrayList<>();
            long end = from + count;
            long line = 0;
            long used = 0;
            long scannedAfter = 0;
            boolean closed = count <= 0; // no more lines are collected
            boolean open = false; // characters seen after the last line end
            boolean prevCr = false;
            StringBuilder cur = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = r.read(buf)) > 0; ) {
                if (closed || line >= end) {
                    scannedAfter += n;
                    if (scannedAfter > MAX_COUNT_SCAN) return new Window(lines, -1);
                }
                for (int i = 0; i < n; i++) {
                    char c = buf[i];
                    if (prevCr) {
                        prevCr = false;
                        if (c == '\n') continue;
                    }
                    if (c == '\n' || c == '\r') {
                        if (!closed && line >= from && line < end) {
                            if (lines.isEmpty() || used + cur.length() <= maxChars) {
                                lines.add(cur.toString());
                                used += cur.length();
                            } else {
                                closed = true;
                            }
                            cur.setLength(0);
                        }
                        line++;
                        open = false;
                        prevCr = c == '\r';
                        continue;
                    }
                    open = true;
                    if (!closed && line >= from && line < end && cur.length() <= maxChars) cur.append(c);
                }
            }
            if (open) {
                if (!closed && line >= from && line < end && (lines.isEmpty() || used + cur.length() <= maxChars)) {
                    lines.add(cur.toString());
                }
                line++;
            }
            return new Window(lines, line);
        }
    }

    /** One requested replacement. */
    public record Edit(String oldString, String newString, boolean replaceAll) {
    }

    /** A replaced range of the LF-normalized text. */
    public record Range(int start, int end, String replacement) {
    }

    /** Result of applying edits: the new text, a short diff and the replaced ranges (sorted, on the original text). */
    public record Applied(String text, String diff, int replacements, List<Range> ranges) {
    }

    /** Applies all edits to the original text or none; throws IllegalArgumentException describing the first failure. */
    public static Applied apply(String text, List<Edit> edits) {
        if (edits.isEmpty()) throw new IllegalArgumentException("No edits given");
        List<Range> ranges = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            Edit e = edits.get(i);
            String label = edits.size() > 1 ? "edit " + (i + 1) + ": " : "";
            String oldS = normalizeEol(e.oldString());
            String newS = normalizeEol(e.newString());
            if (oldS.isEmpty()) throw new IllegalArgumentException(label + "old_string must not be empty");
            for (Range r : locate(text, oldS, newS, e.replaceAll(), label)) ranges.add(r);
        }
        ranges.sort(Comparator.comparingInt(Range::start));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i).start() < ranges.get(i - 1).end()) {
                throw new IllegalArgumentException("Edits overlap; merge them into one edit");
            }
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = ranges.size() - 1; i >= 0; i--) {
            Range r = ranges.get(i);
            sb.replace(r.start(), r.end(), r.replacement());
        }
        return new Applied(sb.toString(), unifiedDiff(text, ranges), ranges.size(), List.copyOf(ranges));
    }

    private static String normalizeEol(String s) {
        return s == null ? "" : s.replace("\r\n", "\n");
    }

    private static List<Range> locate(String text, String oldS, String newS, boolean all, String label) {
        // 1. exact
        List<Integer> hits = occurrences(text, oldS);
        if (!hits.isEmpty()) {
            if (hits.size() > 1 && !all) {
                throw new IllegalArgumentException(label + "old_string occurs " + hits.size()
                        + " times; add context or set replace_all");
            }
            List<Range> out = new ArrayList<>();
            for (int h : hits) out.add(new Range(h, h + oldS.length(), newS));
            return out;
        }
        // 2. trailing whitespace stripped per line, 3. plus unicode punctuation normalized
        for (int stage = 2; stage <= 3; stage++) {
            Norm nt = normalize(text, stage);
            String no = normalize(oldS, stage).s;
            if (no.isEmpty()) continue;
            List<Integer> fuzzy = occurrences(nt.s, no);
            if (fuzzy.size() == 1) {
                int s = fuzzy.get(0);
                return List.of(new Range(nt.map[s], nt.map[s + no.length()], newS));
            }
            if (fuzzy.size() > 1) {
                throw new IllegalArgumentException(label + "old_string matches " + fuzzy.size()
                        + " places after whitespace/unicode normalization; add context");
            }
        }
        throw new IllegalArgumentException(label + "old_string not found." + closest(text, oldS));
    }

    private static List<Integer> occurrences(String text, String sub) {
        List<Integer> out = new ArrayList<>();
        for (int i = text.indexOf(sub); i >= 0; i = text.indexOf(sub, i + sub.length())) out.add(i);
        return out;
    }

    private record Norm(String s, int[] map) {
    }

    /** Normalized text with a map from normalized offsets to original offsets (length = s.length() + 1). */
    private static Norm normalize(String text, int stage) {
        StringBuilder sb = new StringBuilder(text.length());
        int[] map = new int[text.length() + 1];
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                int j = i;
                while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) j++;
                if (j == n || text.charAt(j) == '\n') {
                    i = j; // trailing whitespace dropped
                    continue;
                }
            }
            map[sb.length()] = i;
            sb.append(stage >= 3 ? foldChar(c) : c);
            i++;
        }
        map[sb.length()] = n;
        return new Norm(sb.toString(), java.util.Arrays.copyOf(map, sb.length() + 1));
    }

    private static char foldChar(char c) {
        return switch (c) {
            case '‘', '’', '‚', '‛', '′' -> '\'';
            case '“', '”', '„', '‟', '″' -> '"';
            case '‐', '‑', '‒', '–', '—', '―', '−' -> '-';
            case ' ', ' ', ' ', ' ', ' ', ' ', ' ' -> ' ';
            default -> c;
        };
    }

    /** Hint listing the text lines most similar to the first non-blank line of the missing string. */
    private static String closest(String text, String oldS) {
        String probe = null;
        for (String l : oldS.split("\n")) {
            if (!l.isBlank()) {
                probe = l.strip();
                break;
            }
        }
        if (probe == null) return "";
        String[] lines = text.split("\n", -1);
        record Scored(int line, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            double sc = similarity(probe, lines[i].strip());
            if (sc > 0.4) scored.add(new Scored(i, sc));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (scored.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" Closest lines:");
        for (int i = 0; i < Math.min(3, scored.size()); i++) {
            Scored s = scored.get(i);
            String l = lines[s.line()];
            sb.append("\n  ").append(s.line() + 1).append(": ").append(l.length() > 160 ? l.substring(0, 160) + "..." : l);
        }
        return sb.toString();
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() < 2 || b.length() < 2) return 0;
        java.util.Map<String, Integer> grams = new java.util.HashMap<>();
        for (int i = 0; i < a.length() - 1; i++) grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        int common = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String g = b.substring(i, i + 2);
            Integer c = grams.get(g);
            if (c != null && c > 0) {
                common++;
                grams.put(g, c - 1);
            }
        }
        return 2.0 * common / (a.length() - 1 + b.length() - 1);
    }

    /** Short unified-style diff: one hunk per replaced range, one line of context on each side. */
    private static String unifiedDiff(String text, List<Range> ranges) {
        String[] lines = text.split("\n", -1);
        // offset of the start of every line
        int[] starts = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) starts[i + 1] = starts[i] + lines[i].length() + 1;
        StringBuilder sb = new StringBuilder();
        for (Range r : ranges) {
            int firstLine = lineOf(starts, r.start());
            int lastLine = lineOf(starts, Math.max(r.start(), r.end() - 1));
            int lineEnd = Math.min(text.length(), starts[lastLine + 1] - 1);
            int stop = Math.max(lineEnd, r.end());
            String[] removed = text.substring(starts[firstLine], stop).split("\n", -1);
            String[] addedLines = (text.substring(starts[firstLine], r.start()) + r.replacement()
                    + text.substring(r.end(), stop)).split("\n", -1);
            sb.append("@@ line ").append(firstLine + 1).append(" @@\n");
            if (firstLine > 0) sb.append("  ").append(lines[firstLine - 1]).append('\n');
            for (String l : removed) sb.append("- ").append(l).append('\n');
            for (String l : addedLines) sb.append("+ ").append(l).append('\n');
            if (lastLine + 1 < lines.length) sb.append("  ").append(lines[lastLine + 1]).append('\n');
        }
        return sb.toString();
    }

    private static int lineOf(int[] starts, int offset) {
        int lo = 0, hi = starts.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}

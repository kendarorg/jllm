package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Text file reading/writing that preserves BOM and line endings, plus lenient string replacement. */
public final class TextEdits {

    private TextEdits() {
    }

    /** A file decoded to LF-normalized text, remembering how to write it back. */
    public record TextFile(String text, boolean bom, String eol) {

        public static TextFile read(Path p) throws IOException {
            return decode(Files.readAllBytes(p));
        }

        public static TextFile decode(byte[] bytes) {
            boolean bom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF;
            String s = new String(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0), StandardCharsets.UTF_8);
            int crlf = 0, lf = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    if (i > 0 && s.charAt(i - 1) == '\r') crlf++;
                    else lf++;
                }
            }
            String eol = crlf > lf ? "\r\n" : "\n";
            return new TextFile(s.replace("\r\n", "\n"), bom, eol);
        }

        public byte[] encode(String lfText) {
            String s = "\n".equals(eol) ? lfText : lfText.replace("\n", eol);
            byte[] body = s.getBytes(StandardCharsets.UTF_8);
            if (!bom) return body;
            byte[] out = new byte[body.length + 3];
            out[0] = (byte) 0xEF;
            out[1] = (byte) 0xBB;
            out[2] = (byte) 0xBF;
            System.arraycopy(body, 0, out, 3, body.length);
            return out;
        }

        public void write(Path p, String lfText) throws IOException {
            Files.write(p, encode(lfText));
        }
    }

    /** One requested replacement. */
    public record Edit(String oldString, String newString, boolean replaceAll) {
    }

    private record Range(int start, int end, String replacement) {
    }

    /** Result of applying edits: the new text and a short diff. */
    public record Applied(String text, String diff, int replacements) {
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
        return new Applied(sb.toString(), unifiedDiff(text, ranges), ranges.size());
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

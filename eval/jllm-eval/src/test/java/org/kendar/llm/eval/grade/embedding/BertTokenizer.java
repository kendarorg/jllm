package org.kendar.llm.eval.grade.embedding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT uncased WordPiece tokenizer: control-character cleaning, CJK isolation, lower-casing, accent stripping,
 * whitespace and punctuation splitting, longest-match-first with {@code ##} continuations, {@code [CLS]}/{@code [SEP]}
 * framing and truncation.
 */
public final class BertTokenizer {

    public static final int MAX_LENGTH = 512;
    private static final int MAX_WORD_CHARS = 100;

    private final Map<String, Integer> vocab;
    private final int maxLength;
    private final int cls;
    private final int sep;
    private final int unk;

    public BertTokenizer(Map<String, Integer> vocab, int maxLength) {
        if (maxLength < 3) throw new IllegalArgumentException("maxLength must be at least 3");
        this.vocab = vocab;
        this.maxLength = maxLength;
        this.cls = id("[CLS]");
        this.sep = id("[SEP]");
        this.unk = id("[UNK]");
    }

    /** Loads a vocab.txt: one token per line, the line number is the id. */
    public static BertTokenizer load(Path vocabFile) {
        try {
            List<String> lines = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);
            Map<String, Integer> v = new HashMap<>();
            for (int i = 0; i < lines.size(); i++) v.putIfAbsent(lines.get(i), i);
            return new BertTokenizer(v, MAX_LENGTH);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read vocabulary " + vocabFile + ": " + e.getMessage(), e);
        }
    }

    private int id(String token) {
        Integer i = vocab.get(token);
        if (i == null) throw new IllegalArgumentException("Vocabulary lacks " + token);
        return i;
    }

    /** Token ids with {@code [CLS]} first and {@code [SEP]} last, at most {@code maxLength} long. */
    public int[] encode(String text) {
        List<Integer> ids = new ArrayList<>();
        ids.add(cls);
        int limit = maxLength - 1;
        outer:
        for (String word : words(text)) {
            for (int piece : wordPiece(word)) {
                if (ids.size() >= limit) break outer;
                ids.add(piece);
            }
        }
        ids.add(sep);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Pre-tokenized words: cleaned, lower-cased, accent-stripped, split on whitespace and punctuation. */
    static List<String> words(String text) {
        String normalized = normalize(text == null ? "" : text);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                flush(cur, out);
            } else if (isPunctuation(cp)) {
                flush(cur, out);
                out.add(new String(Character.toChars(cp)));
            } else {
                cur.appendCodePoint(cp);
            }
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static String normalize(String text) {
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue;
            if (isWhitespace(cp)) {
                cleaned.append(' ');
            } else if (isCjk(cp)) {
                cleaned.append(' ').appendCodePoint(cp).append(' ');
            } else {
                cleaned.appendCodePoint(cp);
            }
        }
        String lower = cleaned.toString().toLowerCase(java.util.Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) != Character.NON_SPACING_MARK) out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private List<Integer> wordPiece(String word) {
        if (word.codePointCount(0, word.length()) > MAX_WORD_CHARS) return List.of(unk);
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String sub = word.substring(start, end);
                if (start > 0) sub = "##" + sub;
                Integer id = vocab.get(sub);
                if (id != null) {
                    found = id;
                    break;
                }
                end--;
                if (end > start && Character.isLowSurrogate(word.charAt(end))) end--;
            }
            if (found == null) return List.of(unk);
            pieces.add(found);
            start = end;
        }
        return pieces;
    }

    private static boolean isWhitespace(int cp) {
        if (cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r') return true;
        return Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        int t = Character.getType(cp);
        return t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE
                || t == Character.SURROGATE || t == Character.UNASSIGNED;
    }

    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}

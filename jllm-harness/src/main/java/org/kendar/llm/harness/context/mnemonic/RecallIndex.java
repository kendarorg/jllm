package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical recall over a message log, no embeddings. Messages are split into overlapping passages (about 700 chars,
 * stride 550, snapped to newlines) indexed twice: raw words, and normalized words (identifiers split on camelCase,
 * snake_case and kebab-case, lowercased, crudely stemmed for English and Italian). Ranked by BM25; a strict query
 * (every query word present, a compound identifier whole or as all its parts) marks strong hits, a wide query (any
 * word) adds the rest. Queries with punctuation (paths, {@code key: value}) also match as an exact substring.
 * Built incrementally as the log grows; not persisted (it can always be rebuilt from the log).
 */
final class RecallIndex {

    static final int PASSAGE = 700;
    static final int STRIDE = 550;
    private static final int SNAP = 100;
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int RRF_K = 30;
    private static final Pattern RAW = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern CAMEL = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})"
            + "|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})");
    private static final Pattern PUNCT = Pattern.compile("[/\\\\.:=#]");
    private static final String[] SUFFIXES = {"azioni", "azione", "amente", "zioni", "zione", "mente", "menti",
            "ations", "ation", "ments", "ment", "ness", "ings", "ing", "edly", "ies", "ied", "ed", "es", "ly",
            "ando", "endo", "are", "ere", "ire", "ato", "ata", "ati", "ate", "ito", "ita", "iti", "ite",
            "s", "y", "i", "e", "a", "o"};

    record Passage(int message, String text, Map<String, Integer> raw, Map<String, Integer> norm, int rawLen,
                   int normLen) {
    }

    record Hit(int message, String snippet, double score, boolean strong) {
    }

    private final List<Passage> passages = new ArrayList<>();
    private final Map<String, Set<Integer>> rawPostings = new HashMap<>();
    private final Map<String, Set<Integer>> normPostings = new HashMap<>();
    private long rawTotal;
    private long normTotal;
    private int indexed;
    private String lastHash;

    /** Messages indexed so far. */
    int indexed() {
        return indexed;
    }

    /** Indexes the messages of {@code log} not seen yet; starts over if the indexed part changed. */
    synchronized void update(List<JsonNode> log) {
        if (indexed > log.size() || (indexed > 0 && !Messages.hash(indexed - 1, log.get(indexed - 1)).equals(lastHash))) {
            passages.clear();
            rawPostings.clear();
            normPostings.clear();
            rawTotal = normTotal = 0;
            indexed = 0;
        }
        for (int i = indexed; i < log.size(); i++) add(i, Messages.text(log.get(i)));
        indexed = log.size();
        lastHash = indexed == 0 ? null : Messages.hash(indexed - 1, log.get(indexed - 1));
    }

    private void add(int message, String text) {
        for (int[] span : split(text)) {
            String t = text.substring(span[0], span[1]);
            Map<String, Integer> raw = counts(rawTerms(t));
            Map<String, Integer> norm = counts(normTerms(t));
            int id = passages.size();
            int rawLen = raw.values().stream().mapToInt(Integer::intValue).sum();
            int normLen = norm.values().stream().mapToInt(Integer::intValue).sum();
            passages.add(new Passage(message, t, raw, norm, rawLen, normLen));
            raw.keySet().forEach(k -> rawPostings.computeIfAbsent(k, x -> new HashSet<>()).add(id));
            norm.keySet().forEach(k -> normPostings.computeIfAbsent(k, x -> new HashSet<>()).add(id));
            rawTotal += rawLen;
            normTotal += normLen;
        }
    }

    /** Passage spans: windows of {@link #PASSAGE} chars every {@link #STRIDE}, ends snapped to a nearby newline. */
    static List<int[]> split(String text) {
        List<int[]> out = new ArrayList<>();
        if (text.isBlank()) return out;
        int start = 0;
        while (true) {
            int end = Math.min(text.length(), start + PASSAGE);
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start && end - nl <= SNAP) end = nl;
            }
            out.add(new int[]{start, end});
            if (end >= text.length()) return out;
            int next = start + STRIDE;
            int nl = text.indexOf('\n', next);
            if (nl > 0 && nl - next <= SNAP && nl + 1 < end) next = nl + 1;
            start = Math.min(next, end);
        }
    }

    /**
     * Best passage per message for the queries, merged by reciprocal rank fusion when there is more than one.
     *
     * @param allowed only messages passing this filter are returned
     */
    synchronized List<Hit> search(List<String> queries, IntPredicate allowed, int limit) {
        if (queries.size() == 1) return search(queries.get(0), allowed, limit);
        Map<Integer, Double> fused = new HashMap<>();
        Map<Integer, Hit> best = new HashMap<>();
        for (String q : queries) {
            List<Hit> hits = search(q, allowed, limit * 2);
            for (int r = 0; r < hits.size(); r++) {
                Hit h = hits.get(r);
                fused.merge(h.message(), 1.0 / (RRF_K + r + 1), Double::sum);
                best.merge(h.message(), h, (a, b) -> a.strong() || !b.strong() ? a : b);
            }
        }
        return fused.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()).limit(limit)
                .map(e -> {
                    Hit h = best.get(e.getKey());
                    return new Hit(h.message(), h.snippet(), e.getValue(), h.strong());
                }).toList();
    }

    private List<Hit> search(String query, IntPredicate allowed, int limit) {
        List<String> words = new ArrayList<>();
        Matcher m = RAW.matcher(query);
        while (m.find()) if (!words.contains(m.group())) words.add(m.group());
        String needle = PUNCT.matcher(query).find() ? query.strip().toLowerCase(Locale.ROOT) : null;
        List<String> rawQ = words.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
        List<List<String>> parts = words.stream().map(RecallIndex::normTerms).toList();
        List<String> normQ = parts.stream().flatMap(List::stream).distinct().toList();

        Set<Integer> found = new HashSet<>();
        rawQ.forEach(t -> found.addAll(rawPostings.getOrDefault(t, Set.of())));
        normQ.forEach(t -> found.addAll(normPostings.getOrDefault(t, Set.of())));
        if (needle != null) {
            for (int i = 0; i < passages.size(); i++) {
                if (passages.get(i).text().toLowerCase(Locale.ROOT).contains(needle)) found.add(i);
            }
        }
        Map<Integer, Hit> perMessage = new LinkedHashMap<>();
        for (int id : found) {
            Passage p = passages.get(id);
            if (!allowed.test(p.message())) continue;
            boolean exact = needle != null && p.text().toLowerCase(Locale.ROOT).contains(needle);
            boolean strong = exact || (!words.isEmpty() && strict(p, rawQ, parts));
            double score = bm25(p.raw(), p.rawLen(), rawQ, rawPostings, rawTotal)
                    + bm25(p.norm(), p.normLen(), normQ, normPostings, normTotal) + (exact ? 100 : 0);
            Hit h = new Hit(p.message(), snippet(p.text(), rawQ), score, strong);
            perMessage.merge(p.message(), h, (a, b) -> rank(a) >= rank(b) ? a : b);
        }
        return perMessage.values().stream()
                .sorted((a, b) -> Double.compare(rank(b), rank(a))).limit(limit).toList();
    }

    private static double rank(Hit h) {
        return (h.strong() ? 1_000_000 : 0) + h.score();
    }

    /** Every query word present: whole (raw) or as all its parts (normalized). */
    private static boolean strict(Passage p, List<String> rawQ, List<List<String>> parts) {
        for (int i = 0; i < parts.size(); i++) {
            String raw = i < rawQ.size() ? rawQ.get(i) : null;
            if (raw != null && p.raw().containsKey(raw)) continue;
            if (!parts.get(i).isEmpty() && p.norm().keySet().containsAll(parts.get(i))) continue;
            return false;
        }
        return true;
    }

    private double bm25(Map<String, Integer> tf, int len, Collection<String> terms,
                        Map<String, Set<Integer>> postings, long total) {
        int n = passages.size();
        if (n == 0 || total == 0) return 0;
        double avg = (double) total / n;
        double s = 0;
        for (String t : terms) {
            Integer f = tf.get(t);
            if (f == null) continue;
            int df = postings.getOrDefault(t, Set.of()).size();
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            s += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * len / avg));
        }
        return s;
    }

    private static String snippet(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String t : terms) {
            at = lower.indexOf(t);
            if (at >= 0) break;
        }
        int from = Math.max(0, at < 0 ? 0 : at - 100);
        int to = Math.min(text.length(), from + 300);
        String s = text.substring(from, to).replace('\n', ' ');
        return (from > 0 ? "…" : "") + s + (to < text.length() ? "…" : "");
    }

    static List<String> rawTerms(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = RAW.matcher(text);
        while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
        return out;
    }

    /** Words split on camelCase, snake_case, kebab-case and letter/digit changes, lowercased and stemmed. */
    static List<String> normTerms(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            for (String part : CAMEL.split(m.group())) {
                if (!part.isEmpty()) out.add(stem(part.toLowerCase(Locale.ROOT)));
            }
        }
        return out;
    }

    /** A crude suffix stripper shared by English and Italian: keeps at least three characters. */
    static String stem(String w) {
        if (w.length() <= 4 || !Character.isLetter(w.charAt(0))) return w;
        for (String s : SUFFIXES) {
            if (w.endsWith(s) && w.length() - s.length() >= 3) return w.substring(0, w.length() - s.length());
        }
        return w;
    }

    private static Map<String, Integer> counts(List<String> terms) {
        Map<String, Integer> out = new HashMap<>();
        for (String t : terms) out.merge(t, 1, Integer::sum);
        return out;
    }
}

package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.kendar.llm.harness.context.TokenEstimate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text side of compaction: the transcript sent to the summarizer ({@link #render}, {@link #shorten}) and the repair of
 * an updated summary so it never loses what the earlier one held ({@link #withKeptSections}). No model calls.
 */
public final class SummaryBuilder {

    private static final Logger log = LoggerFactory.getLogger(SummaryBuilder.class);

    static final String SUMMARY_PREFIX = "[Summary of the earlier conversation]\n";
    /** Starts the summary of the older tool rounds of the turn in progress. */
    static final String TURN_SUMMARY_PREFIX = "[Summary of the earlier steps of this request]\n";
    static final int SUMMARY_TOKENS = 3000;
    /** Characters the summary may take: the room every compaction reserves for it ({@link #SUMMARY_TOKENS}). */
    static final int SUMMARY_CHARS = (int) (SUMMARY_TOKENS * TokenEstimate.CHARS_PER_TOKEN);
    /** Highest {@link #render(ArrayNode, int)} level: user text only. */
    static final int MAX_SHRINK = 3;
    private static final String OMITTED = "[earlier history omitted]\n";

    /** Sections an update must never lose: the goal, the facts the user gave and where the work stands. */
    static final List<String> KEPT_SECTIONS = List.of("## Objective", "## User-Stated Facts", "## Work State");
    /** The section whose bullets are merged line by line with the prior summary's (see {@link #keptSections}). */
    static final String FACTS = "## User-Stated Facts";
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(.*)$");
    /** Label of a fact: the words before the first ': ' or '=', else before the first ' is ' / ' are ' / ' was '. */
    private static final Pattern LABEL = Pattern.compile("^(.{1,80}?)(?::\\s|:$|\\s*=|\\s+(?:is|are|was|were)\\s)");
    private static final int MAX_LABEL_WORDS = 8;

    private SummaryBuilder() {
    }

    /** An updated summary after {@link #keptSections}, with the prior user facts that did not fit its size. */
    record Kept(String summary, List<String> droppedFacts) { }

    /** {@link #keptSections} within {@link #SUMMARY_CHARS}; prior facts that do not fit are logged, never fatal. */
    static String withKeptSections(String summary, ArrayNode span) {
        Kept k = keptSections(summary, span, SUMMARY_CHARS);
        if (!k.droppedFacts().isEmpty()) {
            log.warn("Compaction: {} user-stated fact(s) of the previous summary did not fit the summary size ({} chars) "
                    + "and were dropped: {}", k.droppedFacts().size(), SUMMARY_CHARS, k.droppedFacts());
        }
        return k.summary();
    }

    /**
     * {@code summary} with what the model lost while updating an earlier summary copied back from that summary: a
     * weak model rewriting the checkpoint can omit the goal, or keep the facts heading with only the newest fact.
     * <ul>
     *   <li>every {@link #KEPT_SECTIONS} heading the update dropped comes back whole, in front;</li>
     *   <li>under {@link #FACTS}, every bullet of the prior section the update does not repeat (normalised: no
     *   marker, case, markdown emphasis or trailing punctuation) is appended to the section, unless the update
     *   replaced it: a new bullet with the same label, the words before ': ', '=' or ' is ' ("Reviewer is Priya"
     *   is replaced by "Reviewer is Sam").</li>
     * </ul>
     * Restoring never blocks the compaction: the prior facts are added oldest first (their order in the prior
     * summary), each one only while the summary stays within {@code maxChars}; those that do not fit are returned
     * as dropped.
     */
    static Kept keptSections(String summary, ArrayNode span, int maxChars) {
        if (!startsWithSummary(span)) return new Kept(summary, List.of());
        String prior = text(span.get(0)).substring(SUMMARY_PREFIX.length());
        StringBuilder missing = new StringBuilder();
        for (String heading : KEPT_SECTIONS) {
            if (hasHeading(summary, heading)) continue;
            String section = section(prior, heading);
            if (section != null) missing.append(section).append("\n\n");
        }
        String out = missing.isEmpty() ? summary : missing + summary;
        if (!hasHeading(summary, FACTS)) return new Kept(out, List.of());
        List<String> lost = lostFacts(section(prior, FACTS), section(summary, FACTS));
        if (lost.isEmpty()) return new Kept(out, List.of());
        List<String> restored = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int room = maxChars - out.length();
        for (String fact : lost) {
            if (fact.length() + 1 <= room) {
                restored.add(fact);
                room -= fact.length() + 1;
            } else {
                dropped.add(fact);
            }
        }
        return new Kept(restored.isEmpty() ? out : appendToSection(out, FACTS, restored), dropped);
    }

    /** Bullets of {@code prior} that {@code updated} neither repeats nor replaces, in their order, stripped. */
    static List<String> lostFacts(String prior, String updated) {
        if (prior == null) return List.of();
        Set<String> facts = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (String b : bullets(updated)) {
            String n = normalised(b);
            facts.add(n);
            String l = label(n);
            if (l != null) labels.add(l);
        }
        List<String> lost = new ArrayList<>();
        for (String b : bullets(prior)) {
            String n = normalised(b);
            if (n.isEmpty() || facts.contains(n)) continue;
            String l = label(n);
            if (l != null && labels.contains(l)) continue;
            lost.add(b.strip());
        }
        return lost;
    }

    static List<String> bullets(String section) {
        List<String> out = new ArrayList<>();
        if (section == null) return out;
        for (String line : section.split("\n")) if (BULLET.matcher(line).matches()) out.add(line);
        return out;
    }

    /** A bullet without its marker, markdown emphasis, case, extra spaces and trailing punctuation. */
    static String normalised(String bullet) {
        Matcher m = BULLET.matcher(bullet);
        String s = m.matches() ? m.group(1) : bullet;
        s = s.replace("**", "").replace("`", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        return s.replaceAll("[.;,]+$", "").strip();
    }

    /** The label of a normalised fact without a leading "the"; null when it has none or it is too long. */
    static String label(String fact) {
        Matcher m = LABEL.matcher(fact);
        if (!m.find()) return null;
        String l = m.group(1).strip().replaceFirst("^the ", "");
        if (l.isEmpty() || l.split(" ").length > MAX_LABEL_WORDS) return null;
        return l;
    }

    /** {@code text} with {@code lines} added at the end of its {@code heading} section, before the next heading. */
    static String appendToSection(String text, String heading, List<String> lines) {
        List<String> all = new ArrayList<>(List.of(text.split("\n", -1)));
        int start = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).strip().equals(heading)) {
                start = i;
                break;
            }
        }
        int end = start + 1;
        while (end < all.size() && !all.get(end).startsWith("## ")) end++;
        int at = end;
        while (at > start + 1 && all.get(at - 1).isBlank()) at--;
        all.addAll(at, lines);
        return String.join("\n", all);
    }

    static boolean hasHeading(String text, String heading) {
        for (String line : text.split("\n")) if (line.strip().equals(heading)) return true;
        return false;
    }

    /** The {@code heading} section of {@code text} up to the next level-2 heading, stripped; null if absent. */
    static String section(String text, String heading) {
        String[] lines = text.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().equals(heading)) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder(lines[start].strip());
        for (int i = start + 1; i < lines.length && !lines[i].startsWith("## "); i++) sb.append('\n').append(lines[i]);
        return sb.toString().strip();
    }

    private static String text(JsonNode msg) {
        JsonNode c = msg.path("content");
        return c.isTextual() ? c.asText() : c.path(0).path("text").asText("");
    }

    static boolean startsWithSummary(ArrayNode span) {
        if (span.isEmpty()) return false;
        return text(span.get(0)).startsWith(SUMMARY_PREFIX);
    }

    /**
     * {@code span} rendered within {@code cap} chars: an earlier summary that starts the span whole, then the newest
     * history that fits, starting on a line boundary. @return null when the earlier summary alone does not fit
     */
    static String shorten(ArrayNode span, int level, int cap) {
        boolean prior = startsWithSummary(span);
        String head = prior ? render(slice(span, 0, 1), level) : "";
        String rest = render(prior ? slice(span, 1, span.size()) : span, level);
        int avail = cap - head.length();
        if (avail <= 0) return null;
        if (rest.length() <= avail) return head + rest;
        String tail = rest.substring(rest.length() - avail);
        int nl = tail.indexOf('\n');
        if (nl >= 0) tail = tail.substring(nl + 1); // no half line at the start
        return head + OMITTED + tail;
    }

    private static ArrayNode slice(ArrayNode src, int from, int to) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (int i = from; i < to; i++) out.add(src.get(i));
        return out;
    }

    /** Plain-text transcript used for summarization and for the old-vs-new diff view. */
    static String render(ArrayNode messages) {
        return render(messages, 0);
    }

    /**
     * @param level 0 whole exchanges (long outputs clipped); 1 shorter outputs and inputs; 2 no tool outputs; 3 user
     *              text only
     */
    static String render(ArrayNode messages, int level) {
        int inputMax = level == 0 ? 400 : level == 1 ? 200 : 80;
        int resultMax = level == 0 ? 600 : 200;
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : messages) {
            if (level >= 3 && !CutPlanner.isPlainUserText(m)) continue;
            sb.append(m.path("role").asText().toUpperCase()).append(": ");
            JsonNode c = m.path("content");
            if (c.isTextual()) {
                sb.append(c.asText()).append('\n');
                continue;
            }
            for (JsonNode b : c) {
                switch (b.path("type").asText()) {
                    case "text" -> sb.append(b.path("text").asText());
                    case "tool_use" -> sb.append("[tool_use ").append(b.path("name").asText()).append(' ')
                            .append(cut(b.path("input").toString(), inputMax)).append(']');
                    case "tool_result" -> {
                        JsonNode r = b.path("content");
                        if (level >= 2) sb.append("[tool_result omitted]");
                        else sb.append("[tool_result ").append(clip(r.isTextual() ? r.asText() : r.toString(), resultMax))
                                .append(']');
                    }
                    default -> { /* thinking etc. are not worth summarizing */ }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Head and tail of a long text. */
    private static String clip(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max / 2) + " … " + s.substring(s.length() - max / 2);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

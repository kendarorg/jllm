package org.kendar.llm.eval.fixture;

import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A large, deterministic report with facts planted at known lines, evenly spread. The filler never contains an
 * answer (it has no numbers of more than three digits and no capitalized words), so each answer occurs exactly once in {@link #text()}.
 */
public final class LargeOutput {

    private static final String[] QUESTIONS = {
            "What is the tracking number of the backup tape shipment?",
            "Which host runs the nightly billing export?",
            "How many days are audit logs retained?",
            "Who owns the March payments gateway incident?",
            "Which port does the metrics collector listen on?"};
    private static final String[] SENTENCES = {
            "The tracking number of the backup tape shipment is %s.",
            "The nightly billing export runs on host %s.",
            "Audit logs are retained for %s days.",
            "The March payments gateway incident is owned by %s.",
            "The metrics collector listens on port %s."};
    private static final String[] OWNERS = {"Ines Varga", "Tomasz Brandt", "Noor Haddad", "Selma Okoro"};
    private static final String[] RETENTION = {"ninety", "one hundred eighty", "three hundred sixty-five", "seven hundred thirty"};
    private static final String[] WORDS = {"alpha", "bravo", "cedar", "delta", "ember", "fjord", "garnet", "harbor",
            "indigo", "juniper", "kelp", "lumen", "mosaic", "nectar", "onyx", "prairie"};

    private final int lines;
    private final List<Fact> facts = new ArrayList<>();
    private final Map<Fact, Integer> lineOf = new LinkedHashMap<>();
    private final String text;

    /**
     * @param lines     number of lines of the report
     * @param factCount facts to plant, 1 to 5
     * @param seed      makes the answers and the filler reproducible
     */
    public LargeOutput(int lines, int factCount, long seed) {
        if (factCount < 1 || factCount > QUESTIONS.length) throw new IllegalArgumentException("1 to 5 facts");
        if (lines < factCount * 4) throw new IllegalArgumentException("too few lines");
        this.lines = lines;
        Random rnd = new Random(seed);
        String[] answers = {"TRK-" + (1000 + rnd.nextInt(9000)),
                "bill-" + (char) ('a' + rnd.nextInt(26)) + (100 + rnd.nextInt(900)),
                RETENTION[rnd.nextInt(RETENTION.length)],
                OWNERS[rnd.nextInt(OWNERS.length)],
                String.valueOf(20000 + rnd.nextInt(9000))};
        Map<Integer, String> planted = new LinkedHashMap<>();
        for (int i = 0; i < factCount; i++) {
            Fact f = new Fact(QUESTIONS[i], answers[i]);
            int line = lines * (i + 1) / (factCount + 1);
            facts.add(f);
            lineOf.put(f, line);
            planted.put(line, "NOTE " + SENTENCES[i].formatted(answers[i]));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i > 0) sb.append('\n');
            String p = planted.get(i);
            if (p != null) {
                sb.append("line ").append(i).append(' ').append(p);
            } else {
                sb.append("line ").append(i).append(" worker-").append(rnd.nextInt(64)).append(' ')
                        .append(WORDS[rnd.nextInt(WORDS.length)]).append(' ').append(WORDS[rnd.nextInt(WORDS.length)])
                        .append(" status=ok latency=").append(rnd.nextInt(900)).append("ms");
            }
        }
        this.text = sb.toString();
    }

    public String text() {
        return text;
    }

    public int lines() {
        return lines;
    }

    public List<Fact> facts() {
        return List.copyOf(facts);
    }

    /** Zero-based line of the fact in {@link #text()}. */
    public int lineOf(Fact fact) {
        Integer l = lineOf.get(fact);
        if (l == null) throw new IllegalArgumentException("not a fact of this output");
        return l;
    }

    /** {@code big_report}, a READ_ONLY tool that returns the whole report. */
    public HarnessTool tool() {
        return new SimpleTool("big_report", "Returns the full operations report (very long).",
                SimpleTool.schema(), SampleTools.GROUP, ToolKind.READ_ONLY, (ctx, in) -> text);
    }
}

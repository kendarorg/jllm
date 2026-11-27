package org.kendar.llm.eval.grade;

import java.util.Locale;

/**
 * Retention of planted facts, graded by escalation: exact match, then embedding similarity, then the judge only
 * when the similarity is unclear.
 */
public final class RetentionChecks {

    /** Similarity at or above which the answer counts as correct without the judge. */
    public static final double DEFAULT_ACCEPT = 0.85;
    /** Similarity below which the answer counts as wrong without the judge. */
    public static final double DEFAULT_REJECT = 0.50;

    /** A planted fact: the question asked about it and the text the answer must carry. */
    public record Fact(String question, String expected) {
    }

    private RetentionChecks() {
    }

    /** Case, punctuation and spacing insensitive, bounded by word edges: "42" does not match "1428". */
    static boolean exact(String answer, String expected) {
        String a = " " + normalize(answer) + " ";
        String e = normalize(expected);
        return !e.isEmpty() && a.contains(" " + e + " ");
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** All facts are in the final answer. {@code similarity} and {@code judge} may be null. */
    public static Check retained(Similarity similarity, Judge judge, Fact... facts) {
        return retained(similarity, DEFAULT_ACCEPT, DEFAULT_REJECT, judge, facts);
    }

    public static Check retained(Similarity similarity, double accept, double reject, Judge judge, Fact... facts) {
        String n = "retained(" + facts.length + " fact(s))";
        Check.Tier tier = judge != null ? Check.Tier.JUDGE : similarity != null ? Check.Tier.EMBEDDING : Check.Tier.TRACE;
        return Check.of(n, tier, ctx -> {
            String answer = ctx.finalAnswer() == null ? "" : ctx.finalAnswer();
            int byExact = 0;
            int bySimilarity = 0;
            int byJudge = 0;
            for (Fact f : facts) {
                if (exact(answer, f.expected())) {
                    byExact++;
                    continue;
                }
                boolean unclear = true;
                if (similarity != null) {
                    double s = similarity.similarity(answer, f.expected());
                    if (s >= accept) {
                        bySimilarity++;
                        continue;
                    }
                    if (s < reject) unclear = false;
                }
                if (unclear && judge != null) {
                    Judge.Verdict v = judge.judge(f.question(), f.expected(), answer);
                    if (v.correct()) {
                        byJudge++;
                        continue;
                    }
                    return CheckResult.fail(n, "judge rejected '" + f.expected() + "': " + TraceChecks.shorten(v.reason()));
                }
                return CheckResult.fail(n, "fact '" + f.expected() + "' not found in the answer"
                        + (unclear ? " and no judge to decide" : ""));
            }
            return CheckResult.pass(n, facts.length + " fact(s) retained (exact " + byExact + ", embedding "
                    + bySimilarity + ", judge " + byJudge + ")");
        });
    }
}

package org.kendar.llm.eval.grade.embedding;

import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.eval.grade.CheckResult;
import org.kendar.llm.eval.grade.TrialContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares only the final answer with reference answers; the best cosine must reach the threshold. The answer vectors
 * are kept per case for drift detection. With {@code warnOnly} (risk cases) a low score warns instead of failing.
 */
public final class EmbeddingCheck implements Check {

    private final Embedder embedder;
    private final List<String> references;
    private final double threshold;
    private final boolean warnOnly;
    private final Map<String, List<float[]>> vectors = new LinkedHashMap<>();

    public EmbeddingCheck(Embedder embedder, List<String> references, double threshold, boolean warnOnly) {
        if (references.isEmpty()) throw new IllegalArgumentException("At least one reference answer is required");
        this.embedder = embedder;
        this.references = List.copyOf(references);
        this.threshold = threshold;
        this.warnOnly = warnOnly;
    }

    @Override
    public String name() {
        return "embedding-similarity";
    }

    @Override
    public Tier tier() {
        return Tier.EMBEDDING;
    }

    @Override
    public CheckResult check(TrialContext ctx) {
        String answer = ctx.finalAnswer();
        if (answer == null || answer.isBlank()) return low("no final answer");
        float[] a = embedder.embed(answer);
        vectors.computeIfAbsent(ctx.caseName(), k -> new ArrayList<>()).add(a);
        double best = -1;
        for (String ref : references) best = Math.max(best, Embedder.dot(a, embedder.embed(ref)));
        String detail = String.format(Locale.ROOT, "best cosine %.3f, threshold %.3f", best, threshold);
        return best >= threshold ? CheckResult.pass(name(), detail) : low(detail);
    }

    private CheckResult low(String reason) {
        return warnOnly ? CheckResult.warn(name(), reason) : CheckResult.fail(name(), reason);
    }

    /** Final-answer vectors seen so far, by case name, in trial order. */
    public Map<String, List<float[]>> answerVectors() {
        return vectors;
    }
}

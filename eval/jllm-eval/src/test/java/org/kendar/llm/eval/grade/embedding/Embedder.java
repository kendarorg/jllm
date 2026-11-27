package org.kendar.llm.eval.grade.embedding;

import org.kendar.llm.eval.grade.Similarity;

/** Text to unit-length vector; cosine similarity is then the dot product. */
public interface Embedder extends Similarity {

    float[] embed(String text);

    @Override
    default double similarity(String a, String b) {
        return dot(embed(a), embed(b));
    }

    static double dot(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("vector sizes differ: " + a.length + " vs " + b.length);
        double s = 0;
        for (int i = 0; i < a.length; i++) s += (double) a[i] * b[i];
        return s;
    }
}

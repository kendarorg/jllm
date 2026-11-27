package org.kendar.llm.eval.grade;

/** Semantic similarity of two texts (embedding cosine); implemented by the embedding stage. */
@FunctionalInterface
public interface Similarity {

    /** @return cosine similarity, 1 for identical meaning */
    double similarity(String a, String b);
}

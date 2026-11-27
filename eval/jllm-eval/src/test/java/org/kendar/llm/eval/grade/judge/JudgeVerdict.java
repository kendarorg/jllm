package org.kendar.llm.eval.grade.judge;

/**
 * A parsed judge verdict.
 *
 * @param score      1-5
 * @param pass       the judge's pass decision
 * @param reason     the judge's justification
 * @param judge      label of the judge that produced it
 * @param selfGraded true when judge and target are the same model
 * @param cached     true when read from the disk cache
 */
public record JudgeVerdict(int score, boolean pass, String reason, String judge, boolean selfGraded, boolean cached) {
}

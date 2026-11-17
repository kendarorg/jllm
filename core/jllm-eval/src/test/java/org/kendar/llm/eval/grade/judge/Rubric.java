package org.kendar.llm.eval.grade.judge;

/**
 * A judge rubric: what is graded and what low, medium and high look like.
 *
 * @param name   key used by {@code EvalCase.rubric()}
 * @param focus  one sentence on what is being graded
 * @param low    description of a score 1-2 response
 * @param medium description of a score 3 response
 * @param high   description of a score 4-5 response
 */
public record Rubric(String name, String focus, String low, String medium, String high) {

    /** The rubric as prompt text. */
    public String render() {
        return "Rubric \"" + name + "\": " + focus + "\n"
                + "LOW (score 1-2): " + low + "\n"
                + "MEDIUM (score 3): " + medium + "\n"
                + "HIGH (score 4-5): " + high + "\n"
                + "Set pass to true only for a HIGH response.";
    }
}

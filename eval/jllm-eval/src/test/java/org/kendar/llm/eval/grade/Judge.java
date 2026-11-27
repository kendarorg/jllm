package org.kendar.llm.eval.grade;

/** Last-resort grader asking a model whether an answer carries the expected fact; implemented by the judge stage. */
@FunctionalInterface
public interface Judge {

    record Verdict(boolean correct, String reason) {
    }

    Verdict judge(String question, String expected, String answer);
}

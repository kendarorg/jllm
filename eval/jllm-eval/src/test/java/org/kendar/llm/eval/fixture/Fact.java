package org.kendar.llm.eval.fixture;

/** A question with the one answer a document, tool output or conversation really contains. */
public record Fact(String question, String answer) {
}

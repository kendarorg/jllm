package org.kendar.llm.harness.context;

/**
 * Result of a manual {@code /compact}.
 *
 * @param message shown instead of the default report when not null
 */
public record CompactResult(boolean changed, long tokensBefore, long tokensAfter, int clearedToolResults,
                            String message) {

    public CompactResult(boolean changed, long tokensBefore, long tokensAfter, int clearedToolResults) {
        this(changed, tokensBefore, tokensAfter, clearedToolResults, null);
    }
}

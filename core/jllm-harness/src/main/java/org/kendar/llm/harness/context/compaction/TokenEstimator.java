package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.context.TokenEstimate;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.HarnessTool;

/** Size estimates of a request: the messages, the fixed part (system text, tool schemas) and budget scaling. */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** Estimated tokens of {@code messages}, raised to the provider's own figure when {@code lastUsage} is known. */
    public static long estimateTokens(ArrayNode messages, Usage lastUsage) {
        long chars = TokenEstimate.tokens(messages.toString().length());
        return lastUsage == null ? chars : Math.max(chars, lastUsage.contextTokens());
    }

    /** Tokens of the fixed part of a request (system text and tool schemas), estimated from its characters. */
    public static long fixedTokens(RequestPrefix prefix) {
        return TokenEstimate.tokens(fixedChars(prefix));
    }

    /** Characters of the fixed part of a request (system text and tool schemas). */
    public static long fixedChars(RequestPrefix prefix) {
        if (prefix == null) return 0;
        long chars = (prefix.pinned() == null ? 0 : prefix.pinned().length())
                + (prefix.extra() == null ? 0 : prefix.extra().length());
        if (prefix.tools() != null) {
            for (HarnessTool tool : prefix.tools()) {
                chars += tool.name().length() + tool.description().length() + tool.inputSchema().toString().length();
            }
        }
        return chars;
    }

    /** {@code budget} in the unit of the uncorrected estimate. */
    public static long scaled(long budget, double ratio) {
        return ratio > 0 ? (long) (budget / ratio) : budget;
    }
}

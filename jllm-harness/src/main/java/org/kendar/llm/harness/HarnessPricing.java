package org.kendar.llm.harness;

/**
 * Estimated cost from per-million-token prices (USD). Every price defaults to 0, meaning
 * "unpriced" (free/local models); cache read/write tokens are NOT billed at the input price when their own
 * price is 0. Input tokens are assumed to exclude cache tokens (as reported by Anthropic).
 */
public class HarnessPricing {

    private final double inputPerMillion;
    private final double outputPerMillion;
    private final double cacheReadPerMillion;
    private final double cacheWritePerMillion;

    public HarnessPricing(double inputPerMillion, double outputPerMillion, double cacheReadPerMillion,
                          double cacheWritePerMillion) {
        this.inputPerMillion = inputPerMillion;
        this.outputPerMillion = outputPerMillion;
        this.cacheReadPerMillion = cacheReadPerMillion;
        this.cacheWritePerMillion = cacheWritePerMillion;
    }

    public double estimate(long inputTokens, long outputTokens) {
        return estimate(inputTokens, outputTokens, 0, 0);
    }

    public double estimate(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        return (inputTokens * inputPerMillion + outputTokens * outputPerMillion
                + cacheReadTokens * cacheReadPerMillion + cacheWriteTokens * cacheWritePerMillion) / 1_000_000d;
    }
}

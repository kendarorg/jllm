package org.kendar.llm.harness.llm;

public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public Usage plus(Usage o) {
        return new Usage(inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                cacheReadTokens + o.cacheReadTokens, cacheCreationTokens + o.cacheCreationTokens);
    }

    /** Tokens occupying the context window on the request that produced this usage. */
    public long contextTokens() {
        return inputTokens + cacheReadTokens + cacheCreationTokens;
    }
}

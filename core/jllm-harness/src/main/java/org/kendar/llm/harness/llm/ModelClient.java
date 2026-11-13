package org.kendar.llm.harness.llm;

public interface ModelClient {

    ModelResponse complete(ModelRequest request);

    /**
     * Abortable variant: implementations that can, abort the in-flight call (and backoff sleeps) when the token is
     * cancelled, throwing {@link ModelCancelledException}. The default ignores the token.
     */
    default ModelResponse complete(ModelRequest request, CancelToken cancel) {
        return complete(request);
    }

    /**
     * As {@link #complete(ModelRequest, CancelToken)}, telling {@code listener} the parts of the response while they
     * arrive. The returned response is the same a non-streamed call gives for the same content (block order, thinking
     * text and signature, tool input). A client that cannot stream, or has streaming off, just calls
     * {@code complete}. Retries and provider fallback happen only before the first delta; after it a failed call is
     * retried as a whole and {@link StreamListener#onReset} is called first.
     */
    default ModelResponse stream(ModelRequest request, CancelToken cancel, StreamListener listener) {
        return complete(request, cancel);
    }

    /** Single-shot, tool-less completion returning the concatenated text. */
    String simpleComplete(String system, String user, int maxTokens);

    /**
     * One plain request with an explicit sampling temperature (0 = most deterministic), for yes/no decisions. A client
     * that cannot send a temperature (Anthropic) ignores it.
     */
    default String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                  java.util.function.Consumer<Usage> usageSink) {
        return simpleComplete(system, user, maxTokens, usageSink);
    }

    /** As above, reporting the call's token usage to {@code usageSink} (may be null). */
    default String simpleComplete(String system, String user, int maxTokens, java.util.function.Consumer<Usage> usageSink) {
        return simpleComplete(system, user, maxTokens);
    }
}

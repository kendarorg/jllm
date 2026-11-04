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

    /** Single-shot, tool-less completion returning the concatenated text. */
    String simpleComplete(String system, String user, int maxTokens);

    /** As above, reporting the call's token usage to {@code usageSink} (may be null). */
    default String simpleComplete(String system, String user, int maxTokens, java.util.function.Consumer<Usage> usageSink) {
        return simpleComplete(system, user, maxTokens);
    }
}

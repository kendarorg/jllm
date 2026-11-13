package org.kendar.llm.harness.llm;

/** Fills in a default {@link ThinkingLevel} on requests that carry none. */
public final class ThinkingModelClient implements ModelClient {

    private final ModelClient delegate;
    private final ThinkingLevel level;

    public ThinkingModelClient(ModelClient delegate, ThinkingLevel level) {
        if (level == null) throw new IllegalArgumentException("thinking level must not be null");
        this.delegate = delegate;
        this.level = level;
    }

    private ModelRequest apply(ModelRequest r) {
        return r.thinkingLevel() != null ? r : r.withThinkingLevel(level);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return delegate.complete(apply(request));
    }

    @Override
    public ModelResponse complete(ModelRequest request, CancelToken cancel) {
        return delegate.complete(apply(request), cancel);
    }

    @Override
    public ModelResponse stream(ModelRequest request, CancelToken cancel, StreamListener listener) {
        return delegate.stream(apply(request), cancel, listener);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return delegate.simpleComplete(system, user, maxTokens);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 java.util.function.Consumer<Usage> usageSink) {
        return delegate.simpleComplete(system, user, maxTokens, temperature, usageSink);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens,
                                 java.util.function.Consumer<Usage> usageSink) {
        return delegate.simpleComplete(system, user, maxTokens, usageSink);
    }
}

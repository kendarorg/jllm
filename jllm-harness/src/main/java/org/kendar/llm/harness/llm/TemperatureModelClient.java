package org.kendar.llm.harness.llm;

/** Fills in a default sampling temperature on requests that carry none. */
public final class TemperatureModelClient implements ModelClient {

    private final ModelClient delegate;
    private final double temperature;

    public TemperatureModelClient(ModelClient delegate, double temperature) {
        if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("temperature must be 0 to 2");
        this.delegate = delegate;
        this.temperature = temperature;
    }

    private ModelRequest apply(ModelRequest r) {
        return r.temperature() != null ? r : r.withTemperature(temperature);
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
    public String simpleComplete(String system, String user, int maxTokens) {
        return delegate.simpleComplete(system, user, maxTokens);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens,
                                 java.util.function.Consumer<Usage> usageSink) {
        return delegate.simpleComplete(system, user, maxTokens, usageSink);
    }
}

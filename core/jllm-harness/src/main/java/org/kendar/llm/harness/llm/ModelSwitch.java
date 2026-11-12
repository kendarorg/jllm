package org.kendar.llm.harness.llm;

/** The main model client, replaceable at runtime: new runs use the client set by the last {@link #switchTo}. */
public final class ModelSwitch implements ModelClient {

    private volatile ModelClient current;
    private volatile String name;
    private volatile Integer contextTokens;

    public ModelSwitch(ModelClient initial, String name, Integer contextTokens) {
        this.current = initial;
        this.name = name;
        this.contextTokens = contextTokens;
    }

    /** @param contextTokens window of the new model, null if unknown */
    public void switchTo(ModelClient client, String name, Integer contextTokens) {
        this.current = client;
        this.name = name;
        this.contextTokens = contextTokens;
    }

    /** Name of the active provider, or null. */
    public String name() {
        return name;
    }

    public Integer contextTokens() {
        return contextTokens;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return current.complete(request);
    }

    @Override
    public ModelResponse complete(ModelRequest request, CancelToken cancel) {
        return current.complete(request, cancel);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return current.simpleComplete(system, user, maxTokens);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 java.util.function.Consumer<Usage> usageSink) {
        return current.simpleComplete(system, user, maxTokens, temperature, usageSink);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens,
                                 java.util.function.Consumer<Usage> usageSink) {
        return current.simpleComplete(system, user, maxTokens, usageSink);
    }
}

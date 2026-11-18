package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

/** Fills in a default {@link ThinkingLevel} on requests that carry none, helper calls included. */
public final class ThinkingModelClient implements ModelClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return simpleComplete(system, user, maxTokens, null, null);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 java.util.function.Consumer<Usage> usageSink) {
        ArrayNode messages = MAPPER.createArrayNode();
        messages.addObject().put("role", "user").put("content", user);
        ModelRequest request = new ModelRequest(system, "", messages, List.of(), maxTokens);
        if (temperature != null) request = request.withTemperature(temperature);
        ModelResponse r = complete(request);
        if (usageSink != null && r.usage() != null) usageSink.accept(r.usage());
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : r.content()) {
            if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText());
        }
        return sb.toString();
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens,
                                 java.util.function.Consumer<Usage> usageSink) {
        return simpleComplete(system, user, maxTokens, null, usageSink);
    }
}

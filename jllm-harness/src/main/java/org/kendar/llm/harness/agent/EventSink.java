package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface EventSink {

    EventSink NOOP = (type, data) -> {
    };

    void emit(String type, JsonNode data);
}

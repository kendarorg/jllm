package org.kendar.llm.workflows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Json {
    static final ObjectMapper M = new ObjectMapper();

    private Json() {
    }

    static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (JsonProcessingException e) {
            throw new FlowException(FlowException.Kind.SHAPE, "Not valid JSON: " + e.getOriginalMessage());
        }
    }
}

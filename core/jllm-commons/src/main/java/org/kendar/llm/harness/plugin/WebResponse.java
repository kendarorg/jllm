package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** A framework-neutral response. */
public record WebResponse(int status, JsonNode body) {
    public static WebResponse ok(JsonNode body) {
        return new WebResponse(200, body);
    }

    public static WebResponse error(int status, String message) {
        return new WebResponse(status, JsonNodeFactory.instance.objectNode().put("error", message));
    }
}

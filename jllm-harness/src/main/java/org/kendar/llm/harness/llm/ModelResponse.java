package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;

public record ModelResponse(ArrayNode content, String stopReason, Usage usage) {
}

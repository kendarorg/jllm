package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * The request behind {@link ModelClient#simpleComplete}: one user message, no tools, thinking off. Helper calls
 * (tool selection, classification, summaries) have small token limits that a reasoning model would spend on its
 * reasoning before writing any text.
 */
final class SimpleCompletion {

    private static final Logger log = LoggerFactory.getLogger(SimpleCompletion.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How much the one retry raises the limit when the reply was cut before any text. */
    static final int RETRY_FACTOR = 4;

    private SimpleCompletion() {
    }

    /**
     * @param temperature null = not sent
     * @param usageSink   gets the usage of every request made (may be null)
     * @return the concatenated text of the reply
     */
    static String run(ModelClient client, String system, String user, int maxTokens, Double temperature,
                      Consumer<Usage> usageSink) {
        ModelResponse r = call(client, system, user, maxTokens, temperature, usageSink);
        String text = text(r);
        // a model that reasons even when asked not to can use the whole limit before writing anything
        if (text.isBlank() && "max_tokens".equals(r.stopReason()) && maxTokens > 0) {
            int more = maxTokens * RETRY_FACTOR;
            log.warn("A helper call used all its {} tokens before writing any text: retrying with {}", maxTokens, more);
            text = text(call(client, system, user, more, temperature, usageSink));
        }
        return text;
    }

    private static ModelResponse call(ModelClient client, String system, String user, int maxTokens,
                                      Double temperature, Consumer<Usage> usageSink) {
        ArrayNode messages = MAPPER.createArrayNode();
        messages.addObject().put("role", "user").put("content", user);
        ModelRequest request = new ModelRequest(system, "", messages, List.of(), maxTokens,
                null, false, null, ThinkingLevel.MINIMAL);
        if (temperature != null) request = request.withTemperature(temperature);
        ModelResponse r = client.complete(request);
        if (usageSink != null && r.usage() != null) usageSink.accept(r.usage());
        return r;
    }

    private static String text(ModelResponse r) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : r.content()) {
            if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText());
        }
        return sb.toString();
    }
}

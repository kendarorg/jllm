package org.kendar.llm.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.agent.AgentEvent;

import java.util.List;

/**
 * Everything recorded during one trial.
 *
 * @param events      harness events in order
 * @param requests    model requests in order (main loop and summary calls)
 * @param finalAnswer text of the last assistant message of the last turn
 * @param error       failure of the run itself (timeout, exception), null when it ended normally
 */
public record TrialTrace(List<AgentEvent> events, List<RecordedRequest> requests, String finalAnswer, String error,
                         long millis) {

    public List<AgentEvent> events(String type) {
        return events.stream().filter(e -> e.type().equals(type)).toList();
    }

    /** The {@code tool_call} events. */
    public List<AgentEvent> toolCalls() {
        return events("tool_call");
    }

    public static String toolName(AgentEvent e) {
        JsonNode d = e.data();
        return d == null ? "" : d.path("name").asText("");
    }

    /** Requests of the agent loop, without summary and classifier calls. */
    public List<RecordedRequest> mainRequests() {
        return requests.stream().filter(r -> !r.simple()).toList();
    }
}

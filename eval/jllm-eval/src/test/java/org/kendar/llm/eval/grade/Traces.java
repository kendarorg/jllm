package org.kendar.llm.eval.grade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.eval.run.RecordedRequest;
import org.kendar.llm.eval.run.TrialTrace;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.llm.Usage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hand-built traces for the grader tests. */
final class Traces {

    static final ObjectMapper M = new ObjectMapper();

    private final List<AgentEvent> events = new ArrayList<>();
    private final List<RecordedRequest> requests = new ArrayList<>();
    private String answer = "";
    private String error;

    static Traces trace() {
        return new Traces();
    }

    Traces event(String type, String json) {
        try {
            events.add(new AgentEvent(events.size(), type, M.readTree(json)));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    Traces call(String id, String name, String inputJson) {
        return event("tool_call", "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}");
    }

    Traces result(String id, String name, boolean isError, String output) {
        ObjectNode d = M.createObjectNode().put("id", id).put("name", name).put("isError", isError).put("output", output);
        events.add(new AgentEvent(events.size(), "tool_result", d));
        return this;
    }

    Traces answer(String a) {
        this.answer = a;
        return this;
    }

    Traces error(String e) {
        this.error = e;
        return this;
    }

    Traces request(String system, List<String> tools, Usage usage, JsonNode... messages) {
        ArrayNode arr = M.createArrayNode();
        for (JsonNode m : messages) arr.add(m);
        requests.add(new RecordedRequest(requests.size(), RecordedRequest.handoffNotes(arr), system, null, arr, tools, 1000, usage, 1));
        return this;
    }

    TrialTrace build() {
        return new TrialTrace(events, requests, answer, error, 1);
    }

    TrialContext ctx() {
        return ctx(Path.of("."), Map.of(), Map.of());
    }

    TrialContext ctx(Path ws, Map<String, String> before, Map<String, Object> extras) {
        return new TrialContext("t", build(), ws, before, List.of("q"), extras);
    }

    static ObjectNode msg(String role, String text) {
        ObjectNode m = M.createObjectNode().put("role", role);
        m.put("content", text);
        return m;
    }

    static ObjectNode toolResultMsg(String id, String text) {
        ObjectNode m = M.createObjectNode().put("role", "user");
        m.putArray("content").addObject().put("type", "tool_result").put("tool_use_id", id).put("content", text);
        return m;
    }

    static Usage usage(long in, long read, long create) {
        return new Usage(in, 10, read, create);
    }
}

package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** The shared context every step and action sees. Results and decisions are append-only. */
public final class FlowContext {
    private final String runId;
    private final String user;
    private final JsonNode input;
    private final ObjectNode results = Json.M.createObjectNode();
    private final List<Decision> decisions = new ArrayList<>();
    private JsonNode error;
    private JsonNode requests = Json.M.createObjectNode();

    public record Decision(String stepId, String question, String outcome, String decidedBy, JsonNode inputs) {
    }

    public FlowContext(String runId, String user, JsonNode input) {
        this.runId = runId;
        this.user = user;
        this.input = input == null ? Json.M.createObjectNode() : input;
    }

    public String runId() {
        return runId;
    }

    public String user() {
        return user;
    }

    public JsonNode input() {
        return input;
    }

    public JsonNode results() {
        return results;
    }

    public JsonNode result(String stepId) {
        return results.path(stepId);
    }

    void putResult(String stepId, JsonNode v) {
        results.set(stepId, v);
    }

    public List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    void addDecision(Decision d) {
        decisions.add(d);
    }

    /** Data set by accepted customer updates, by key. */
    public JsonNode requests() {
        return requests;
    }

    void requests(JsonNode r) {
        this.requests = r;
    }

    /** Only set while an error handler runs. */
    public JsonNode error() {
        return error == null ? Json.M.missingNode() : error;
    }

    void error(JsonNode e) {
        this.error = e;
    }

    /** Resolves a dotted path such as {@code input.order.id} or {@code steps.load.amount}. */
    public JsonNode resolve(String path) {
        String[] parts = path.trim().split("[.]");
        JsonNode cur = switch (parts[0]) {
            case "input" -> input;
            case "steps" -> results;
            case "error" -> error();
            case "requests" -> requests;
            case "user" -> Json.M.getNodeFactory().textNode(user);
            case "runId" -> Json.M.getNodeFactory().textNode(runId);
            default -> Json.M.missingNode();
        };
        for (int i = 1; i < parts.length; i++) cur = cur.path(parts[i]);
        return cur;
    }

    /** Replaces every {@code {{path}}} slot; a missing value is an error, never an empty string. */
    public String render(String template) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int s = template.indexOf("{{", i);
            if (s < 0) {
                out.append(template, i, template.length());
                return out.toString();
            }
            int e = template.indexOf("}}", s);
            if (e < 0) throw new FlowException(FlowException.Kind.INVALID_FLOW, "Unclosed slot in: " + template);
            out.append(template, i, s);
            String path = template.substring(s + 2, e);
            JsonNode v = resolve(path);
            if (v.isMissingNode() || v.isNull()) {
                throw new FlowException(FlowException.Kind.INVALID_FLOW, "Slot {{" + path.trim() + "}} has no value");
            }
            out.append(v.isValueNode() ? v.asText() : v.toString());
            i = e + 2;
        }
    }
}

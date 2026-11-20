package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Everything stored for one run: its snapshot, input, status, journal and history. The journal is what lets a run
 * stop for days and continue without repeating a completed step: results, decisions, answers and failures are
 * recorded by step key, and a resume walks the flow again, taking recorded values instead of executing.
 */
public final class RunRecord {
    public static final String PENDING = "pending", RUNNING = "running", WAITING = "waiting",
            COMPLETED = "completed", FAILED = "failed", CANCELLED = "cancelled";

    public final String runId;
    public final String user;
    public final JsonNode flow;
    public final JsonNode input;
    public String status = PENDING;
    public JsonNode result;
    public JsonNode error;
    /** The open question, with {@code deadline} (epoch ms) when it has a waiting time. */
    public ObjectNode pending;
    public final ObjectNode results = Json.M.createObjectNode();
    public final ObjectNode decisions = Json.M.createObjectNode();
    public final ObjectNode deciders = Json.M.createObjectNode();
    /** Why a chooser decided as it did (for example a gate's value and reason), by step key. */
    public final ObjectNode decisionDetails = Json.M.createObjectNode();
    public final ObjectNode answers = Json.M.createObjectNode();
    /** Nesting: the parent step that started this run, how deep it is, the depth it may not exceed, flows above it. */
    public String parentRunId;
    public String parentStep;
    public int depth = 1;
    public int maxDepth = FlowDef.DEFAULT_DEPTH;
    public ArrayNode chain = Json.M.createArrayNode();
    /** Child runs started by this run, by step key. */
    public final ObjectNode children = Json.M.createObjectNode();
    public final ObjectNode failures = Json.M.createObjectNode();
    public ArrayNode history = Json.M.createArrayNode();
    /** Customer requests received, newest last, and the data set by accepted updates (by key). */
    public ArrayNode requests = Json.M.createArrayNode();
    public final ObjectNode requestData = Json.M.createObjectNode();
    /** Redirects taken; each one starts a fresh set of journal keys. {@code redirectTo} is where the walk starts. */
    public int epoch;
    public String redirectTo;
    /** Epoch ms: when the run was created, and when it completed, failed or was cancelled (0 while it is open). */
    public long startedAt = System.currentTimeMillis();
    public long endedAt;

    public RunRecord(String runId, String user, JsonNode flow, JsonNode input) {
        this.runId = runId;
        this.user = user;
        this.flow = flow;
        this.input = input;
    }

    public ObjectNode toJson() {
        ObjectNode o = Json.M.createObjectNode();
        o.put("runId", runId).put("user", user).put("status", status);
        o.set("flow", flow);
        o.set("input", input);
        if (result != null) o.set("result", result);
        if (error != null) o.set("error", error);
        if (pending != null) o.set("pending", pending);
        ObjectNode j = o.putObject("journal");
        j.set("results", results);
        j.set("decisions", decisions);
        j.set("deciders", deciders);
        j.set("decisionDetails", decisionDetails);
        j.set("answers", answers);
        j.set("failures", failures);
        o.set("history", history);
        if (parentRunId != null) o.put("parentRunId", parentRunId).put("parentStep", parentStep);
        o.put("depth", depth).put("maxDepth", maxDepth);
        o.set("chain", chain);
        o.set("children", children);
        o.set("requests", requests);
        o.set("requestData", requestData);
        o.put("epoch", epoch);
        o.put("startedAt", startedAt).put("endedAt", endedAt);
        if (redirectTo != null) o.put("redirectTo", redirectTo);
        return o;
    }

    public static RunRecord fromJson(JsonNode o) {
        RunRecord r = new RunRecord(o.path("runId").asText(), o.path("user").asText(), o.path("flow"), o.path("input"));
        r.status = o.path("status").asText();
        if (o.has("result")) r.result = o.get("result");
        if (o.has("error")) r.error = o.get("error");
        if (o.has("pending")) r.pending = (ObjectNode) o.get("pending");
        r.results.setAll((ObjectNode) o.path("journal").path("results"));
        r.decisions.setAll((ObjectNode) o.path("journal").path("decisions"));
        r.deciders.setAll((ObjectNode) o.path("journal").path("deciders"));
        if (o.path("journal").path("decisionDetails").isObject()) {
            r.decisionDetails.setAll((ObjectNode) o.path("journal").get("decisionDetails"));
        }
        r.answers.setAll((ObjectNode) o.path("journal").path("answers"));
        r.failures.setAll((ObjectNode) o.path("journal").path("failures"));
        r.history = (ArrayNode) o.path("history");
        if (o.has("parentRunId")) {
            r.parentRunId = o.get("parentRunId").asText();
            r.parentStep = o.path("parentStep").asText();
        }
        r.depth = o.path("depth").asInt(1);
        r.maxDepth = o.path("maxDepth").asInt(FlowDef.DEFAULT_DEPTH);
        r.chain = (ArrayNode) o.path("chain");
        r.children.setAll((ObjectNode) o.path("children"));
        if (o.has("requests")) r.requests = (ArrayNode) o.get("requests");
        r.requestData.setAll((ObjectNode) o.path("requestData"));
        r.epoch = o.path("epoch").asInt();
        r.startedAt = o.path("startedAt").asLong(0);
        r.endedAt = o.path("endedAt").asLong(0);
        if (o.has("redirectTo")) r.redirectTo = o.get("redirectTo").asText();
        return r;
    }
}

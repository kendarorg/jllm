package org.kendar.llm.workflows;

import org.kendar.llm.harness.workflows.WorkflowApi.QuestionKind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Questions and answers of a run: building a question from a step, recording a validated answer, delivering customer
 * requests to the run that accepts them, and the shapes a model answer must have.
 */
final class FlowRequests {
    private final Registry registry;
    private final RunStore store;
    private final Consumer<String> cancel;
    private final Function<RunRecord, FlowEngine.RunResult> execute;

    /**
     * @param cancel  cancels a run and everything below it
     * @param execute walks a run and continues the parent that waits for it
     */
    FlowRequests(Registry registry, RunStore store, Consumer<String> cancel, Function<RunRecord, FlowEngine.RunResult> execute) {
        this.registry = registry;
        this.store = store;
        this.cancel = cancel;
        this.execute = execute;
    }

    RunRecord waitingRun(String runId) {
        RunRecord rec = store.load(runId).orElseThrow(() -> new FlowException(FlowException.Kind.INVALID_FLOW,
                "Unknown run " + runId));
        if (!RunRecord.WAITING.equals(rec.status) || rec.pending == null) {
            throw new FlowException(FlowException.Kind.INVALID_FLOW, "Run " + runId + " is " + rec.status + ", not waiting");
        }
        return rec;
    }

    /** Validates the answer to the run's pending question and records it; a refused answer throws. */
    static void storeAnswer(RunRecord rec, JsonNode answer, String by) {
        Question q = Question.fromJson(rec.pending);
        q.validate(answer);
        ObjectNode a = Json.M.createObjectNode();
        a.set("value", answer);
        a.put("by", by);
        rec.answers.set(q.key(), a);
    }

    static Question buildQuestion(FlowContext ctx, String key, JsonNode s, List<Question.Option> options) {
        String title = s.path("question").asText(s.path("text").asText(""));
        String text = s.has("text") ? ctx.render(s.get("text").asText()) : title;
        QuestionKind kind = QuestionKind.valueOf(s.path("kind").asText("single").toUpperCase());
        return new Question(key, kind, title, text, options, s.path("min").asInt(kind == QuestionKind.SINGLE ? 1 : 0),
                s.path("max").asInt(kind == QuestionKind.SINGLE ? 1 : 0), s.path("required").asBoolean(true),
                s.path("who").asText("owner"), s.has("pattern") ? s.get("pattern").asText() : null,
                s.has("facts") ? StepRunner.render(ctx, s.get("facts")) : null);
    }

    /**
     * Delivers a customer request to the innermost waiting run that accepts this kind at its current stage, then
     * upward. Kinds nobody accepts go to a question-only helper if declared, else are refused.
     */
FlowEngine.RequestOutcome request(String runId, String kind, JsonNode payload, String by) {
    List<RunRecord> chain = new ArrayList<>();
    RunRecord cur = waitingRun(runId);
    chain.add(cur);
    while (cur.pending.has("childRunId")) {
        cur = waitingRun(cur.pending.get("childRunId").asText());
        chain.add(cur);
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
        RunRecord rec = chain.get(i);
        JsonNode decl = acceptedAt(rec, kind);
        if (decl.isMissingNode()) continue;
        return applyRequest(rec, decl, kind, payload, by);
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
        RunRecord rec = chain.get(i);
        String helper = stepOf(rec).path("helper").asText(rec.flow.path("helper").asText(null));
        if (helper == null) continue;
        Registry.FlowAction a = registry.action(helper);
        if (a == null) continue;
        FlowContext c = new FlowContext(rec.runId, rec.user, rec.input);
        c.requests(rec.requestData);
        Registry.ActionCall call = new Registry.ActionCall(rec.runId, rec.pending.path("stage").asText(), c, payload,
                Json.M.createObjectNode(), q -> {
                    throw new FlowException(FlowException.Kind.REQUEST, "A helper may not ask questions");
                });
        JsonNode answer;
        try {
            answer = a.run(call);
        } catch (FlowException e) {
            throw e;
        } catch (Exception e) { // boundary: helper boundary: any failure is a FlowException
            throw new FlowException(FlowException.Kind.REQUEST, "Helper failed: " + e.getMessage(), e);
        }
        record(rec, kind, payload, by, "helper");
        store.save(rec);
        return new FlowEngine.RequestOutcome(rec.runId, "helper", answer, null);
    }
    RunRecord inner = chain.get(chain.size() - 1);
    record(inner, kind, payload, by, "refused");
    store.save(inner);
    throw new FlowException(FlowException.Kind.REQUEST, "Request '" + kind + "' is not accepted at stage '"
            + inner.pending.path("stage").asText() + "'");
}

private static JsonNode stepOf(RunRecord rec) {
    String stage = rec.pending.path("stage").asText();
    JsonNode s = rec.flow.path("steps").path(stage);
    return s.isMissingNode() ? rec.flow.path("onError").path("steps").path(stage) : s;
}

private static JsonNode acceptedAt(RunRecord rec, String kind) {
    JsonNode d = stepOf(rec).path("accepts").path(kind);
    return d.isMissingNode() ? rec.flow.path("accepts").path(kind) : d;
}

private static void record(RunRecord rec, String kind, JsonNode payload, String by, String outcome) {
    ObjectNode o = rec.requests.addObject();
    o.put("at", Instant.now().toString()).put("kind", kind).put("by", by).put("outcome", outcome);
    o.set("payload", payload);
}

private FlowEngine.RequestOutcome applyRequest(RunRecord rec, JsonNode decl, String kind, JsonNode payload, String by) {
    String action = decl.path("action").asText();
    switch (action) {
        case "cancel" -> {
            record(rec, kind, payload, by, "cancelled");
            cancel.accept(rec.runId);
            if (rec.parentRunId != null) {
                store.load(rec.parentRunId).filter(p -> RunRecord.WAITING.equals(p.status))
                        .ifPresent(execute::apply);
            }
            return new FlowEngine.RequestOutcome(rec.runId, action, null, null);
        }
        case "update" -> {
            QuestionKind k = QuestionKind.valueOf(decl.path("kind").asText("text").toUpperCase());
            List<Question.Option> opts = new ArrayList<>();
            decl.path("options").fields().forEachRemaining(e -> opts.add(new Question.Option(e.getKey(), e.getValue().asText())));
            new Question(kind, k, kind, kind, opts, decl.path("min").asInt(0), decl.path("max").asInt(0),
                    decl.path("required").asBoolean(true), "customer",
                    decl.has("pattern") ? decl.get("pattern").asText() : null, null).validate(payload);
            rec.requestData.set(decl.path("key").asText(kind), payload);
            record(rec, kind, payload, by, "updated");
            store.save(rec);
            return new FlowEngine.RequestOutcome(rec.runId, action, null, null);
        }
        case "redirect" -> {
            String to = decl.path("to").asText();
            if (!rec.flow.path("steps").has(to)) {
                throw new FlowException(FlowException.Kind.REQUEST, "Redirect target '" + to + "' does not exist");
            }
            record(rec, kind, payload, by, "redirected");
            rec.children.fields().forEachRemaining(c -> cancel.accept(c.getValue().asText()));
            rec.pending = null;
            rec.epoch++;
            rec.redirectTo = to;
            rec.status = RunRecord.RUNNING;
            return new FlowEngine.RequestOutcome(rec.runId, action, null, execute.apply(rec));
        }
        default -> throw new FlowException(FlowException.Kind.REQUEST, "Unknown request action '" + action + "'");
    }
}

/** Enforced answer shapes: {@code "text"} or an object of field name to type (a trailing {@code ?} = optional). */
static final class AnswerShape {
    private AnswerShape() {
    }

    static JsonNode validate(JsonNode shape, String raw) {
        if (shape.isMissingNode() || "text".equals(shape.asText())) {
            return Json.M.getNodeFactory().textNode(raw == null ? "" : raw.trim());
        }
        JsonNode n = Json.parse(stripFences(raw));
        if (!n.isObject()) throw new FlowException(FlowException.Kind.SHAPE, "Answer is not a JSON object");
        for (Iterator<Map.Entry<String, JsonNode>> it = shape.fields(); it.hasNext(); ) {
            var e = it.next();
            String field = e.getKey();
            boolean optional = field.endsWith("?");
            if (optional) field = field.substring(0, field.length() - 1);
            JsonNode v = n.path(field);
            if (v.isMissingNode() || v.isNull()) {
                if (optional) continue;
                throw new FlowException(FlowException.Kind.SHAPE, "Answer misses field '" + field + "'");
            }
            boolean ok = switch (e.getValue().asText()) {
                case "string" -> v.isTextual();
                case "number" -> v.isNumber();
                case "boolean" -> v.isBoolean();
                case "array" -> v.isArray();
                case "object" -> v.isObject();
                default -> false;
            };
            if (!ok) {
                throw new FlowException(FlowException.Kind.SHAPE,
                        "Field '" + field + "' must be a " + e.getValue().asText());
            }
        }
        return n;
    }

    private static String stripFences(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) t = t.substring(nl + 1, end).trim();
        }
        return t;
    }
}
}

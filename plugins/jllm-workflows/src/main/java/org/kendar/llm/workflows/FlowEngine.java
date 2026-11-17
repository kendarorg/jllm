package org.kendar.llm.workflows;

import org.kendar.llm.harness.workflows.WorkflowApi.QuestionKind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Walks a flow: the engine, not the model, decides the order. Chaining of results, retries, turning points,
 * questions, the error handler, and stopping and resuming live here.
 *
 * <p>Resuming works by walking the flow again over the run's journal: a step already journaled returns its recorded
 * result without executing, a decision returns its recorded outcome, a question its recorded answer. The walk is
 * deterministic, so it reaches the first unanswered question again and stops there.
 */
public final class FlowEngine {
    private static final int MAX_STEP_EXECUTIONS = 1000;

    public enum Status { COMPLETED, FAILED, WAITING, CANCELLED }

    /** One line of the flow history. */
    public record Event(Instant at, String step, String kind, String detail, JsonNode data) {
    }

    public record RunResult(String runId, Status status, JsonNode result, JsonNode error, List<Event> history,
                            Map<String, String> decisions, FlowContext context, Question pending) {
    }

    /** Result of delivering a customer request: which run took it, what was done, the helper's answer if any. */
    public record RequestOutcome(String handledBy, String action, JsonNode answer, RunResult run) {
    }

    private record Finish(String outcome, JsonNode result, String retryStep) {
    }

    /** A step that failed after its own retries; carries what the handler gets to see. */
    private static final class StepFailure extends RuntimeException {
        final FlowException error;
        final String step;
        final int attempts;

        StepFailure(String step, FlowException error, int attempts) {
            super(error.getMessage(), error);
            this.step = step;
            this.error = error;
            this.attempts = attempts;
        }
    }

    /** Thrown to stop the walk at a question nobody has answered yet. No stack trace: it is control flow. */
    static final class WaitSignal extends RuntimeException {
        final Question question;
        final long deadline;
        final String childRunId;

        WaitSignal(Question q, long deadline, String childRunId) {
            super("waiting for " + q.key(), null, false, false);
            this.question = q;
            this.deadline = deadline;
            this.childRunId = childRunId;
        }
    }

    private final Registry registry;
    private final RunStore store;
    private final FlowLibrary library;

    public FlowEngine(Registry registry, RunStore store, FlowLibrary library) {
        this.registry = registry;
        this.store = store;
        this.library = library;
    }

    /** In-memory store, no child flows. */
    public static FlowEngine simple(Registry registry) {
        return new FlowEngine(registry, new RunStore.Memory(), FlowLibrary.empty());
    }

    public RunStore store() {
        return store;
    }

    public RunResult run(FlowDef flow, JsonNode input, String user) {
        return run(flow, input, user, Map.of());
    }

    /**
     * @param recorded decisions of an earlier run of the same flow, keyed {@code step#occurrence}; a recorded decision
     *                 is reused instead of asking the chooser again
     */
    public RunResult run(FlowDef flow, JsonNode input, String user, Map<String, String> recorded) {
        return execute(prepare(flow, input, user, recorded), false);
    }

    /** Checks and stores a new run (status pending) without walking it; {@link #proceed} does the walking. */
    public RunRecord prepare(FlowDef flow, JsonNode input, String user, Map<String, String> recorded) {
        List<String> problems = FlowValidator.check(flow, registry);
        if (!problems.isEmpty()) {
            throw new FlowException(FlowException.Kind.INVALID_FLOW, "Flow '" + flow.name() + "' is invalid: " + problems);
        }
        List<String> childProblems = FlowLibrary.checkChildren(flow, library, user);
        if (!childProblems.isEmpty()) {
            throw new FlowException(FlowException.Kind.INVALID_FLOW,
                    "Flow '" + flow.name() + "' has bad children: " + childProblems);
        }
        FlowDef snap = flow.snapshot(); // edits of the original never disturb this run
        RunRecord rec = new RunRecord(UUID.randomUUID().toString(), user, snap.doc(),
                input == null ? Json.M.createObjectNode() : input);
        rec.maxDepth = flow.limit("depth", null, FlowDef.DEFAULT_DEPTH); // the started flow decides for the chain
        rec.chain.add(flow.scope() + "/" + flow.name());
        recorded.forEach(rec.decisions::put);
        store.save(rec);
        return rec;
    }

    /**
     * Records the answer of a waiting run (or of the child it waits for) and marks it running, without walking it;
     * {@link #proceed} does the walking. A refused answer throws and leaves the question open.
     */
    public void accept(String runId, JsonNode answer, String by) {
        RunRecord rec = waitingRun(runId);
        if (rec.pending.has("childRunId")) {
            accept(rec.pending.get("childRunId").asText(), answer, by);
            return;
        }
        Question q = Question.fromJson(rec.pending);
        q.validate(answer);
        ObjectNode a = Json.M.createObjectNode();
        a.set("value", answer);
        a.put("by", by);
        rec.answers.set(q.key(), a);
        rec.status = RunRecord.RUNNING;
        store.save(rec);
    }

    /** Walks a run made by {@link #prepare} or {@link #accept}; for a run waiting on a child, the child that was accepted. */
    public void proceed(String runId) {
        RunRecord rec = store.load(runId).orElseThrow(() -> new FlowException(FlowException.Kind.INVALID_FLOW,
                "Unknown run " + runId));
        while (RunRecord.WAITING.equals(rec.status) && rec.pending != null && rec.pending.has("childRunId")) {
            rec = store.load(rec.pending.get("childRunId").asText()).orElse(null);
            if (rec == null) return;
        }
        if (RunRecord.PENDING.equals(rec.status) || RunRecord.RUNNING.equals(rec.status)) execute(rec, true);
    }

    /**
     * Gives the answer of a waiting run and continues it. A refused answer throws and leaves the question open.
     *
     * @param by who gave the answer; recorded in the history
     */
    public RunResult resume(String runId, JsonNode answer, String by) {
        return resume(runId, answer, by, true);
    }

    private RunResult resume(String runId, JsonNode answer, String by, boolean propagate) {
        RunRecord rec = waitingRun(runId);
        if (rec.pending.has("childRunId")) { // waiting propagates upward: the answer belongs to the child
            resume(rec.pending.get("childRunId").asText(), answer, by, false);
        } else {
            Question q = Question.fromJson(rec.pending);
            q.validate(answer);
            ObjectNode a = Json.M.createObjectNode();
            a.set("value", answer);
            a.put("by", by);
            rec.answers.set(q.key(), a);
        }
        return execute(rec, propagate);
    }

    /** Ends every waiting run whose waiting time has passed: the step takes its timeout path or fails. */
    public List<RunResult> expire(long nowMs) {
        List<RunResult> out = new ArrayList<>();
        for (RunRecord r : store.listAll()) {
            if (RunRecord.WAITING.equals(r.status) && r.pending != null && r.pending.has("deadline")
                    && r.pending.get("deadline").asLong() <= nowMs) {
                if (r.pending.has("childRunId")) continue; // the child that waits is expired on its own
                ObjectNode a = Json.M.createObjectNode();
                a.put("timeout", true);
                r.answers.set(r.pending.path("key").asText(), a);
                out.add(execute(r, true));
            }
        }
        return out;
    }

    /** Cancelling is its own end state; it does not go through the error handler. */
    public void cancel(String runId) {
        RunRecord rec = store.load(runId).orElseThrow(() -> new FlowException(FlowException.Kind.INVALID_FLOW,
                "Unknown run " + runId));
        if (rec.status.equals(RunRecord.COMPLETED) || rec.status.equals(RunRecord.FAILED)) return;
        rec.status = RunRecord.CANCELLED;
        rec.endedAt = System.currentTimeMillis();
        rec.pending = null;
        store.save(rec);
        rec.children.fields().forEachRemaining(c -> cancel(c.getValue().asText())); // cancelling a parent cancels below
    }

    /**
     * Delivers a customer request to the innermost waiting run that accepts this kind at its current stage, then
     * upward. Kinds nobody accepts go to a question-only helper if declared, else are refused.
     */
    public RequestOutcome request(String runId, String kind, JsonNode payload, String by) {
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
                    q -> {
                        throw new FlowException(FlowException.Kind.REQUEST, "A helper may not ask questions");
                    });
            JsonNode answer;
            try {
                answer = a.run(call);
            } catch (FlowException e) {
                throw e;
            } catch (Exception e) {
                throw new FlowException(FlowException.Kind.REQUEST, "Helper failed: " + e.getMessage(), e);
            }
            record(rec, kind, payload, by, "helper");
            store.save(rec);
            return new RequestOutcome(rec.runId, "helper", answer, null);
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

    private RequestOutcome applyRequest(RunRecord rec, JsonNode decl, String kind, JsonNode payload, String by) {
        String action = decl.path("action").asText();
        switch (action) {
            case "cancel" -> {
                record(rec, kind, payload, by, "cancelled");
                cancel(rec.runId);
                if (rec.parentRunId != null) {
                    store.load(rec.parentRunId).filter(p -> RunRecord.WAITING.equals(p.status))
                            .ifPresent(p -> execute(p, true));
                }
                return new RequestOutcome(rec.runId, action, null, null);
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
                return new RequestOutcome(rec.runId, action, null, null);
            }
            case "redirect" -> {
                String to = decl.path("to").asText();
                if (!rec.flow.path("steps").has(to)) {
                    throw new FlowException(FlowException.Kind.REQUEST, "Redirect target '" + to + "' does not exist");
                }
                record(rec, kind, payload, by, "redirected");
                rec.children.fields().forEachRemaining(c -> cancel(c.getValue().asText()));
                rec.pending = null;
                rec.epoch++;
                rec.redirectTo = to;
                rec.status = RunRecord.RUNNING;
                return new RequestOutcome(rec.runId, action, null, execute(rec, true));
            }
            default -> throw new FlowException(FlowException.Kind.REQUEST, "Unknown request action '" + action + "'");
        }
    }

    private RunRecord waitingRun(String runId) {
        RunRecord rec = store.load(runId).orElseThrow(() -> new FlowException(FlowException.Kind.INVALID_FLOW,
                "Unknown run " + runId));
        if (!RunRecord.WAITING.equals(rec.status) || rec.pending == null) {
            throw new FlowException(FlowException.Kind.INVALID_FLOW, "Run " + runId + " is " + rec.status + ", not waiting");
        }
        return rec;
    }

    /** @param propagate when the run ends or changes while a parent waits for it, continue the parent too */
    private RunResult execute(RunRecord rec, boolean propagate) {
        FlowDef flow = FlowDef.parse(rec.flow.toString());
        Run r = new Run(rec, flow);
        rec.status = RunRecord.RUNNING;
        store.save(rec); // visible as running while the walk (a model call can take minutes) is in progress
        RunResult res = r.go();
        store.save(rec);
        if (propagate && rec.parentRunId != null) {
            store.load(rec.parentRunId).filter(p -> RunRecord.WAITING.equals(p.status)).ifPresent(p -> execute(p, true));
        }
        return res;
    }

    private final class Run {
        final RunRecord rec;
        final FlowDef flow;
        final FlowContext ctx;
        final Map<String, Integer> occurrences = new LinkedHashMap<>();
        final List<Event> history = new ArrayList<>();
        final Set<String> askedInStep = new HashSet<>();
        int executions;
        String curStep = "";

        Run(RunRecord rec, FlowDef flow) {
            this.rec = rec;
            this.flow = flow;
            this.ctx = new FlowContext(rec.runId, rec.user, rec.input);
            this.ctx.requests(rec.requestData);
        }

        void log(String step, String kind, String detail, JsonNode data) {
            history.add(new Event(Instant.now(), step, kind, detail, data));
            // progress is visible while the run walks, not only at its end
            rec.history = historyNode();
            store.save(rec);
        }

        RunResult go() {
            String startAt = rec.redirectTo != null ? rec.redirectTo : flow.start();
            String mainPrefix = rec.epoch > 0 ? "r" + rec.epoch + ":" : "";
            int handlerRetries = 0;
            int handlerRetryLimit = flow.handler().path("retryLimit").asInt(FlowDef.DEFAULT_RETRIES);
            log("", "start", flow.name(), ctx.input());
            for (JsonNode q : rec.requests) log("", "request", q.path("kind").asText() + " " + q.path("outcome").asText(), q);
            while (true) {
                try {
                    Finish f = walk(flow.steps(), startAt, mainPrefix);
                    log("", "completed", "", f.result());
                    return done(Status.COMPLETED, f.result(), null);
                } catch (WaitSignal w) {
                    return waiting(w);
                } catch (StepFailure sf) {
                    ObjectNode err = errorNode(sf);
                    log(sf.step, "error", sf.error.kind() + ": " + sf.error.getMessage(), err);
                    ctx.error(err);
                    Finish h;
                    try {
                        h = walk(flow.handler().path("steps"), flow.handler().path("start").asText(), "h:");
                    } catch (WaitSignal w) {
                        return waiting(w);
                    } catch (StepFailure hf) {
                        ObjectNode both = errorNode(hf);
                        both.set("original", err);
                        log(hf.step, "handler-error", hf.error.getMessage(), both);
                        return done(Status.FAILED, null, both);
                    }
                    log("", "handler", h.outcome(), h.result());
                    switch (h.outcome()) {
                        case "recovered" -> {
                            ObjectNode res = Json.M.createObjectNode();
                            res.put("recovered", true);
                            res.set("value", h.result());
                            ctx.error(null);
                            return done(Status.COMPLETED, res, null);
                        }
                        case "retry" -> {
                            if (++handlerRetries > handlerRetryLimit) {
                                ObjectNode lim = errorNode(sf);
                                lim.put("limit", "handler retries (" + handlerRetryLimit + ")");
                                log(sf.step, "limit", "handler retry limit hit", lim);
                                return done(Status.FAILED, null, lim);
                            }
                            ctx.error(null);
                            startAt = h.retryStep();
                        }
                        default -> {
                            return done(Status.FAILED, null, err);
                        }
                    }
                }
            }
        }

        RunResult waiting(WaitSignal w) {
            rec.status = RunRecord.WAITING;
            rec.pending = w.question.toJson();
            if (w.deadline > 0) rec.pending.put("deadline", w.deadline);
            if (w.childRunId != null) rec.pending.put("childRunId", w.childRunId);
            rec.pending.put("stage", curStep);
            log("", "waiting", w.question.key(), rec.pending);
            return result(Status.WAITING, null, null, w.question);
        }

        RunResult done(Status s, JsonNode result, JsonNode error) {
            rec.status = s == Status.COMPLETED ? RunRecord.COMPLETED : RunRecord.FAILED;
            rec.result = result;
            rec.error = error;
            rec.endedAt = System.currentTimeMillis();
            rec.pending = null;
            return result(s, result, error, null);
        }

        ArrayNode historyNode() {
            ArrayNode h = Json.M.createArrayNode();
            for (Event e : history) {
                ObjectNode o = h.addObject();
                o.put("at", e.at().toString()).put("step", e.step()).put("kind", e.kind()).put("detail", e.detail());
                if (e.data() != null) o.set("data", e.data());
            }
            return h;
        }

        RunResult result(Status s, JsonNode result, JsonNode error, Question pending) {
            rec.history = historyNode();
            Map<String, String> d = new LinkedHashMap<>();
            rec.decisions.fields().forEachRemaining(e -> d.put(e.getKey(), e.getValue().asText()));
            return new RunResult(ctx.runId(), s, result, error, List.copyOf(history), d, ctx, pending);
        }

        ObjectNode errorNode(StepFailure f) {
            ObjectNode e = Json.M.createObjectNode();
            e.put("step", f.step).put("kind", f.error.kind().name()).put("message", f.error.getMessage());
            e.put("attempts", f.attempts);
            if (f.error.getCause() != null) e.put("cause", String.valueOf(f.error.getCause()));
            return e;
        }

        StepFailure failureFrom(JsonNode f) {
            FlowException fe = new FlowException(FlowException.Kind.valueOf(f.path("kind").asText()),
                    f.path("message").asText());
            return new StepFailure(f.path("step").asText(), fe, f.path("attempts").asInt());
        }

        Finish walk(JsonNode steps, String start, String prefix) {
            String cur = start;
            while (true) {
                if (++executions > MAX_STEP_EXECUTIONS) {
                    throw new StepFailure(cur, new FlowException(FlowException.Kind.LIMIT,
                            "More than " + MAX_STEP_EXECUTIONS + " step executions; the flow loops"), 0);
                }
                JsonNode s = steps.path(cur);
                String type = s.path("type").asText();
                if (type.equals("finish")) {
                    final String at = cur;
                    JsonNode res = guard(cur, () -> render(s.path("result")));
                    log(at, "finish", s.path("outcome").asText(""), res);
                    return new Finish(s.path("outcome").asText("completed"), res, s.path("retryStep").asText(null));
                }
                curStep = cur;
                int occ = occurrences.merge(prefix + cur, 1, Integer::sum);
                String key = prefix + cur + "#" + occ;
                askedInStep.clear();
                final String at = cur;
                if (type.equals("decide")) {
                    cur = decide(at, key, s);
                    continue;
                }
                if (type.equals("ask")) {
                    String next = ask(at, key, s);
                    cur = next;
                    continue;
                }
                JsonNode out;
                if (rec.results.has(key)) {
                    out = rec.results.get(key);
                    log(at, type, "replayed", out);
                } else if (rec.failures.has(key)) {
                    throw failureFrom(rec.failures.get(key));
                } else {
                    try {
                        out = withRetries(at, key, s);
                    } catch (StepFailure sf) {
                        rec.failures.set(key, errorNode(sf));
                        throw sf;
                    }
                    rec.results.set(key, out);
                    log(at, type, "", out);
                }
                ctx.putResult(at, out);
                cur = s.path("next").asText();
            }
        }

        <T> T guard(String step, java.util.function.Supplier<T> body) {
            try {
                return body.get();
            } catch (FlowException e) {
                throw new StepFailure(step, e, 1);
            }
        }

        JsonNode render(JsonNode template) {
            if (template.isMissingNode()) return Json.M.createObjectNode();
            if (template.isTextual()) return Json.M.getNodeFactory().textNode(ctx.render(template.asText()));
            if (template.isObject()) {
                ObjectNode o = Json.M.createObjectNode();
                for (Iterator<Map.Entry<String, JsonNode>> it = template.fields(); it.hasNext(); ) {
                    var e = it.next();
                    o.set(e.getKey(), render(e.getValue()));
                }
                return o;
            }
            return template;
        }

        // ---- questions

        /** The recorded answer, or the timeout marker; stops the walk when there is neither. */
        JsonNode answerFor(String stepKey, String stepId, JsonNode step, Question q) {
            askedInStep.add(q.key());
            int limit = flow.limit("questions", step, FlowDef.DEFAULT_QUESTIONS);
            if (askedInStep.size() > limit) {
                throw new FlowException(FlowException.Kind.LIMIT,
                        "Step '" + stepId + "' asks more than " + limit + " questions (limit: questions)");
            }
            JsonNode a = rec.answers.get(q.key());
            if (a != null) {
                if (a.path("timeout").asBoolean(false)) return null;
                return a;
            }
            long wait = step.path("waitMs").asLong(0);
            throw new WaitSignal(q, wait > 0 ? System.currentTimeMillis() + wait : 0, null);
        }

        Question buildQuestion(String key, JsonNode s, List<Question.Option> options) {
            String title = s.path("question").asText(s.path("text").asText(""));
            String text = s.has("text") ? ctx.render(s.get("text").asText()) : title;
            QuestionKind kind = QuestionKind.valueOf(s.path("kind").asText("single").toUpperCase());
            Question q = new Question(key, kind, title, text, options, s.path("min").asInt(kind == QuestionKind.SINGLE ? 1 : 0),
                    s.path("max").asInt(kind == QuestionKind.SINGLE ? 1 : 0), s.path("required").asBoolean(true),
                    s.path("who").asText("owner"), s.has("pattern") ? s.get("pattern").asText() : null,
                    s.has("facts") ? render(s.get("facts")) : null);
            return q;
        }

        String ask(String id, String key, JsonNode s) {
            List<Question.Option> opts = new ArrayList<>();
            s.path("options").fields().forEachRemaining(e -> opts.add(new Question.Option(e.getKey(), e.getValue().asText())));
            Question q = guard(id, () -> buildQuestion(key, s, opts));
            JsonNode a = guard(id, () -> answerFor(key, id, s, q));
            if (a == null) return timeoutTarget(id, s, key);
            ctx.putResult(id, a.path("value"));
            rec.results.set(key, a.path("value"));
            log(id, "answer", "by " + a.path("by").asText(), a.path("value"));
            return s.path("next").asText();
        }

        String timeoutTarget(String id, JsonNode s, String key) {
            log(id, "timeout", key, null);
            if (s.has("onTimeout")) return s.get("onTimeout").asText();
            throw new StepFailure(id, new FlowException(FlowException.Kind.TIMEOUT,
                    "Step '" + id + "' waited too long and has no timeout path"), 1);
        }

        // ---- steps

        JsonNode withRetries(String id, String key, JsonNode step) {
            int retries = flow.limit("retries", step, FlowDef.DEFAULT_RETRIES);
            long wait = step.path("retryWaitMs").asLong(0);
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    return execute(id, key, step, attempt);
                } catch (FlowException e) {
                    boolean temporary = e.kind() == FlowException.Kind.ACTION || e.kind() == FlowException.Kind.SHAPE
                            || (e.kind() == FlowException.Kind.CHILD && step.has("retries"));
                    if (!temporary || attempt > retries) throw new StepFailure(id, e, attempt);
                    log(id, "retry", "attempt " + attempt + " failed: " + e.getMessage(), null);
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new StepFailure(id, new FlowException(FlowException.Kind.ACTION, "interrupted"), attempt);
                        }
                    }
                }
            }
        }

        JsonNode execute(String id, String key, JsonNode step, int attempt) {
            return switch (step.path("type").asText()) {
                case "action" -> callAction(step.path("action").asText(), id, key, step, null);
                case "call" -> callChild(id, key, step, key + "@" + attempt);
                case "task" -> runTask(id, key, step, flow.tasks().path(step.path("task").asText()));
                default -> throw new FlowException(FlowException.Kind.INVALID_FLOW, "Cannot execute " + id);
            };
        }

        Registry.ActionCall call(String id, String key, JsonNode step, JsonNode taskResult) {
            Registry.Asker asker = q -> {
                Question scoped = new Question(key + "/" + q.key(), q.kind(), q.title(), q.text(), q.options(), q.min(),
                        q.max(), q.required(), q.who(), q.pattern(), q.facts());
                JsonNode a = answerFor(key, id, step, scoped);
                if (a == null) {
                    throw new FlowException(FlowException.Kind.TIMEOUT, "Question '" + q.key() + "' timed out");
                }
                return a.path("value");
            };
            return new Registry.ActionCall(ctx.runId(), id, ctx, taskResult, asker);
        }

        JsonNode callAction(String name, String id, String key, JsonNode step, JsonNode taskResult) {
            Registry.FlowAction a = registry.action(name);
            if (a == null) throw new FlowException(FlowException.Kind.PERMISSION, "Action '" + name + "' is not available");
            try {
                JsonNode r = a.run(call(id, key, step, taskResult));
                return r == null ? Json.M.nullNode() : r;
            } catch (FlowException | WaitSignal e) {
                throw e;
            } catch (Exception e) {
                throw new FlowException(FlowException.Kind.ACTION, "Action '" + name + "' failed: " + e.getMessage(), e);
            }
        }

        JsonNode callChild(String id, String key, JsonNode step, String childKey) {
            String name = step.path("flow").asText(null);
            String scope = step.path("scope").asText(FlowLibrary.PERSONAL);
            if (!rec.children.has(childKey)) {
                if (name == null) { // a Java chooser may name the flow, but only from the declared set
                    String picked = javaChoose(id, key, step);
                    boolean allowed = false;
                    for (JsonNode n : step.path("allowed")) allowed |= n.asText().equals(picked);
                    if (!allowed) {
                        throw new FlowException(FlowException.Kind.CHOOSER,
                                "Chooser named flow '" + picked + "', which is not in the allowed set");
                    }
                    name = picked;
                    log(id, "child-chosen", name, null);
                }
                if (FlowLibrary.GLOBAL.equals(flow.scope()) && FlowLibrary.PERSONAL.equals(scope)) {
                    throw new FlowException(FlowException.Kind.PERMISSION, "A global flow cannot call a personal flow");
                }
                final String childName = name;
                FlowDef cf = library.find(scope, name, rec.user).orElseThrow(() -> new FlowException(
                        FlowException.Kind.PERMISSION, "Flow '" + childName + "' does not exist or is not allowed"));
                int depth = rec.depth + 1;
                if (depth > rec.maxDepth) {
                    throw new FlowException(FlowException.Kind.LIMIT,
                            "Nesting depth " + depth + " exceeds the limit " + rec.maxDepth + " (limit: depth)");
                }
                String ck = scope + "/" + name;
                for (JsonNode c : rec.chain) {
                    if (c.asText().equals(ck)) {
                        throw new FlowException(FlowException.Kind.LIMIT, "Flow '" + name + "' would call itself: " + rec.chain);
                    }
                }
                List<String> problems = FlowValidator.check(cf, registry);
                if (!problems.isEmpty()) {
                    throw new FlowException(FlowException.Kind.INVALID_FLOW, "Child '" + name + "' is invalid: " + problems);
                }
                RunRecord child = new RunRecord(UUID.randomUUID().toString(), rec.user, cf.snapshot().doc(), render(step.path("input")));
                child.parentRunId = rec.runId;
                child.parentStep = key;
                child.depth = depth;
                // a child may lower the depth limit for what it starts, never raise it
                child.maxDepth = Math.min(rec.maxDepth, cf.limit("depth", null, rec.maxDepth));
                child.chain.addAll(rec.chain);
                child.chain.add(ck);
                rec.children.put(childKey, child.runId);
                log(id, "child-start", name + " " + child.runId, null);
                FlowEngine.this.execute(child, false);
            }
            String childId = rec.children.get(childKey).asText();
            RunRecord child = store.load(childId).orElseThrow(() -> new FlowException(FlowException.Kind.CHILD,
                    "Child run " + childId + " is missing"));
            switch (child.status) {
                case RunRecord.COMPLETED:
                    return child.result == null ? Json.M.nullNode() : child.result;
                case RunRecord.WAITING:
                    throw new WaitSignal(Question.fromJson(child.pending), child.pending.path("deadline").asLong(0), childId);
                default:
                    throw new FlowException(FlowException.Kind.CHILD, "Child flow ended " + child.status
                            + (child.error == null ? "" : ": " + child.error.path("message").asText()),
                            new RuntimeException(child.error == null ? child.status : child.error.toString()));
            }
        }

        JsonNode runTask(String id, String key, JsonNode step, JsonNode task) {
            if (task.has("before")) {
                ctx.putResult(id + "_before", callAction(task.get("before").asText(), id, key, step, null));
            }
            JsonNode result;
            if (task.has("instead")) {
                result = callAction(task.get("instead").asText(), id, key, step, null);
            } else {
                result = callModel(id, key, step, task);
            }
            if (task.has("after")) {
                JsonNode replaced = callAction(task.get("after").asText(), id, key, step, result);
                if (!replaced.isNull() && !replaced.isMissingNode()) result = replaced;
            }
            return result;
        }

        /** Rounds of a task that proceeds or asks after the model's reply was taken for a question. */
        static final int MAX_CLARIFY = 3;

        static final String CLASSIFY_SYSTEM = "You read the reply of an AI assistant that works on a task and decide whether "
                + "it stops to ask the user something. Answer with exactly one word.\n"
                + "none: the reply is a finished result, not a question.\n"
                + "proceed: it asks something the assistant could decide itself with a sensible default or assumption "
                + "(names, style, small choices, confirmations).\n"
                + "ask: it cannot go on without information only the user has (missing requirements, credentials, a choice "
                + "with real consequences).";

        JsonNode callModel(String id, String key, JsonNode step, JsonNode task) {
            Registry.ModelGateway gw = registry.model();
            if (gw == null) throw new FlowException(FlowException.Kind.PERMISSION, "No model is configured");
            String prompt = ctx.render(task.path("prompt").asText());
            boolean clarify = task.path("clarify").asBoolean(true) && registry.classifier() != null;
            boolean unattended = flow.doc().path("unattended").asBoolean(false);
            String raw;
            boolean waiting = false;
            try {
            for (int round = 0; ; round++) {
                // every reply and verdict is journaled: a resume after a question never calls the model twice
                String rawKey = key + "/raw:" + round;
                if (rec.results.has(rawKey)) {
                    raw = rec.results.get(rawKey).asText();
                } else {
                    raw = runModel(id, key, step, task, prompt, gw);
                    rec.results.put(rawKey, raw);
                }
                if (!clarify || round >= MAX_CLARIFY) break;
                String verdict = verdict(key + "/clarify:" + round, raw);
                if (verdict.equals("none")) break;
                if (verdict.equals("ask") && unattended) verdict = "proceed"; // nobody to ask
                if (verdict.equals("proceed")) {
                    log(id, "clarify", "proceed", Json.M.createObjectNode().put("round", round));
                    prompt += "\n\nYour previous reply was:\n" + raw + "\n\nDo not ask questions. Use your best judgement, "
                            + "state your assumptions briefly, and complete the task.";
                    continue;
                }
                String shown = raw.length() > 2000 ? raw.substring(0, 2000) + "…" : raw;
                Question q = new Question(key + "/clarify:" + round + "/ask", QuestionKind.TEXT,
                        "The model needs an answer", shown, List.of(), 0, 0, true, "owner", null, null);
                JsonNode a = answerFor(key, id, step, q);
                if (a == null) throw new FlowException(FlowException.Kind.TIMEOUT, "The question of step '" + id + "' timed out");
                log(id, "clarify", "ask", Json.M.createObjectNode().put("round", round));
                prompt += "\n\nYour previous reply was:\n" + raw + "\n\nThe user answered:\n" + a.path("value").asText()
                        + "\n\nContinue and complete the task.";
            }
            return AnswerShape.validate(task.path("answer"), raw);
            } catch (WaitSignal w) {
                waiting = true; // the replies stay journaled: the resume must not call the model again
                throw w;
            } finally {
                if (!waiting) { // the step ends (or fails and is retried): the next run asks the model afresh
                    String prefix = key + "/";
                    List<String> drop = new ArrayList<>();
                    rec.results.fieldNames().forEachRemaining(k -> {
                        if (k.startsWith(prefix + "raw:") || k.startsWith(prefix + "clarify:")) drop.add(k);
                    });
                    drop.forEach(rec.results::remove);
                }
            }
        }

        /** none, proceed or ask for a reply; a reply that cannot be a question is settled without the model. */
        String verdict(String vKey, String raw) {
            if (rec.results.has(vKey)) return rec.results.get(vKey).asText();
            String v = "none";
            if (looksLikeQuestion(raw)) {
                try {
                    String r = registry.classifier().classify(rec.user, CLASSIFY_SYSTEM, "Reply:\n" + (raw.length() > 1500 ? raw.substring(raw.length() - 1500) : raw)).strip().toLowerCase();
                    v = r.startsWith("ask") ? "ask" : r.startsWith("proceed") ? "proceed" : "none";
                } catch (org.kendar.llm.harness.auth.LoginRequiredException e) {
                    throw e; // a missing login is not a classifier failure: it reaches the user
                } catch (Exception e) {
                    v = "none"; // a failing classifier never blocks the flow
                }
            }
            rec.results.put(vKey, v);
            return v;
        }

        /** Cheap pre-filter: the reply ends in a question, or says it waits for the user. */
        static boolean looksLikeQuestion(String raw) {
            String t = raw.strip();
            if (t.isEmpty()) return false;
            String tail = t.length() > 600 ? t.substring(t.length() - 600) : t;
            String low = tail.toLowerCase();
            return t.endsWith("?") || tail.contains("?") && (low.contains("please") || low.contains("let me know")
                    || low.contains("would you") || low.contains("do you") || low.contains("which "))
                    || low.contains("please confirm") || low.contains("please provide") || low.contains("let me know");
        }

        String runModel(String id, String key, JsonNode step, JsonNode task, String prompt, Registry.ModelGateway gw) {
            String title = "flow: " + flow.name() + " " + rec.runId.substring(0, Math.min(8, rec.runId.length()));
            Set<String> approved = approvedTools(key);
            while (true) {
                try {
                    return gw.complete(new Registry.ModelCall(task.path("agent").asText(), task.path("system").asText(""),
                            prompt, rec.user, title, Set.copyOf(approved), taskTools(task)));
                } catch (Registry.ApprovalNeededException e) {
                    approve(id, key, step, e, approved);
                } catch (FlowException | WaitSignal e) {
                    throw e; // e.g. a refused approval, already classified
                } catch (Exception e) {
                    throw new FlowException(FlowException.Kind.ACTION, "Model call failed: " + e.getMessage(), e);
                }
            }
        }

        /** The task's {@code tools}: absent = the agent's own (null), "*" = all of the role, or a list of tool names. */
        static List<String> taskTools(JsonNode task) {
            JsonNode t = task.path("tools");
            if (t.isMissingNode() || t.isNull()) return null;
            if (t.isTextual()) return List.of(t.asText());
            List<String> out = new ArrayList<>();
            t.forEach(n -> out.add(n.asText()));
            return out;
        }

        static final String APPROVE = "approve";

        /** Tools the user already allowed for this step, from the recorded answers (so a resume asks nothing twice). */
        Set<String> approvedTools(String key) {
            Set<String> out = new LinkedHashSet<>();
            String prefix = key + "/approve:";
            rec.answers.fieldNames().forEachRemaining(k -> {
                if (k.startsWith(prefix) && APPROVE.equals(rec.answers.get(k).path("value").asText())) {
                    out.add(k.substring(prefix.length()));
                }
            });
            return out;
        }

        /**
         * A tool of the task's agent needs an approval. An unattended flow fails; otherwise the user is asked once
         * per tool, the run waits for the answer, and the task is run again with the allowed tools.
         */
        void approve(String id, String key, JsonNode step, Registry.ApprovalNeededException e, Set<String> approved) {
            if (flow.doc().path("unattended").asBoolean(false)) {
                throw new FlowException(FlowException.Kind.PERMISSION, e.getMessage()
                        + " (the flow is unattended, nobody can approve)", e);
            }
            boolean asked = false;
            for (String tool : e.tools()) {
                if (approved.contains(tool)) continue;
                asked = true;
                Question q = new Question(key + "/approve:" + tool, QuestionKind.SINGLE,
                        "Allow the tool '" + tool + "'?",
                        "The task of step '" + id + "' wants to call '" + tool + "', which needs your approval.",
                        List.of(new Question.Option(APPROVE, "Allow"), new Question.Option("deny", "Refuse")),
                        1, 1, true, "owner", null, null);
                JsonNode a = answerFor(key, id, step, q);
                if (a == null) {
                    throw new FlowException(FlowException.Kind.TIMEOUT, "Approval of '" + tool + "' timed out");
                }
                if (!APPROVE.equals(a.path("value").asText())) {
                    throw new FlowException(FlowException.Kind.PERMISSION,
                            "The user refused the tool '" + tool + "' for step '" + id + "'", e);
                }
                approved.add(tool);
            }
            if (!asked) { // approved and still refused (a rule or a hook): asking again would loop
                throw new FlowException(FlowException.Kind.PERMISSION, e.getMessage(), e);
            }
        }

        String decide(String id, String key, JsonNode s) {
            JsonNode outcomes = s.path("outcomes");
            String by = s.path("by").asText();
            String outcome;
            String decidedBy = by;
            if (rec.decisions.has(key)) { // replay: never ask again
                outcome = rec.decisions.get(key).asText();
                decidedBy = rec.deciders.has(key) ? rec.deciders.get(key).asText() : "recorded";
            } else {
                String raw;
                if (by.equals("user")) {
                    List<Question.Option> opts = new ArrayList<>();
                    outcomes.fieldNames().forEachRemaining(o -> opts.add(new Question.Option(o, o)));
                    Question q = guard(id, () -> buildQuestion(key, withKind(s), opts));
                    JsonNode a = guard(id, () -> answerFor(key, id, s, q));
                    if (a == null) return timeoutTarget(id, s, key);
                    String valid = a.path("value").asText();
                    raw = valid;
                    decidedBy = "user:" + a.path("by").asText();
                } else {
                    raw = guard(id, () -> by.equals("java") ? javaChoose(id, key, s) : llmChoose(s));
                }
                outcome = raw == null ? "" : raw.trim();
            }
            String target = outcomes.has(outcome) ? outcomes.get(outcome).asText() : null;
            if (target == null) {
                if (s.has("default")) {
                    log(id, "decision-invalid", "'" + outcome + "' is not declared; using default", null);
                    target = s.get("default").asText();
                    outcome = "default";
                } else {
                    final String bad = outcome;
                    throw new StepFailure(id, new FlowException(FlowException.Kind.CHOOSER,
                            "Chooser returned '" + bad + "', not one of " + names(outcomes)), 1);
                }
            }
            rec.decisions.put(key, outcome);
            if (!rec.deciders.has(key)) rec.deciders.put(key, decidedBy);
            ctx.addDecision(new FlowContext.Decision(id, s.path("question").asText(""), outcome, decidedBy,
                    Json.M.createObjectNode()));
            ObjectNode d = Json.M.createObjectNode();
            d.put("outcome", outcome).put("decidedBy", decidedBy).put("target", target);
            log(id, "decision", s.path("question").asText(""), d);
            return target;
        }

        JsonNode withKind(JsonNode s) {
            ObjectNode c = s.deepCopy();
            c.put("kind", "single");
            return c;
        }

        String javaChoose(String id, String key, JsonNode s) {
            Registry.Chooser c = registry.chooser(s.path("chooser").asText());
            if (c == null) throw new FlowException(FlowException.Kind.PERMISSION, "Chooser is not available");
            try {
                return c.choose(call(id, key, s, null));
            } catch (FlowException | WaitSignal e) {
                throw e;
            } catch (Exception e) {
                throw new FlowException(FlowException.Kind.CHOOSER, "Chooser failed: " + e.getMessage(), e);
            }
        }

        String llmChoose(JsonNode s) {
            Registry.ModelGateway gw = registry.model();
            if (gw == null) throw new FlowException(FlowException.Kind.PERMISSION, "No model is configured");
            String prompt = (s.has("prompt") ? ctx.render(s.get("prompt").asText()) : "")
                    + "\n\nQuestion: " + s.path("question").asText()
                    + "\nAnswer with exactly one of these words and nothing else: " + names(s.path("outcomes"));
            try {
                return gw.complete(new Registry.ModelCall(s.path("agent").asText(), "You choose one option from a closed list.",
                        prompt, rec.user, null, Set.of(), List.of()));
            } catch (FlowException e) {
                throw e;
            } catch (Exception e) {
                throw new FlowException(FlowException.Kind.CHOOSER, "Model chooser failed: " + e.getMessage(), e);
            }
        }

        List<String> names(JsonNode outcomes) {
            List<String> l = new ArrayList<>();
            outcomes.fieldNames().forEachRemaining(l::add);
            return l;
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

    /** Convenience for tests and callers: the history as one line per event. */
    public static List<String> lines(RunResult r) {
        List<String> out = new ArrayList<>();
        for (Event e : r.history()) out.add(e.step() + ":" + e.kind() + (e.detail().isEmpty() ? "" : " " + e.detail()));
        return out;
    }
}

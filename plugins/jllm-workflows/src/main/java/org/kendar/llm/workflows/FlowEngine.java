package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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


    private final Registry registry;
    private final RunStore store;
    private final FlowLibrary library;
    private final FlowRequests requests;

    public FlowEngine(Registry registry, RunStore store, FlowLibrary library) {
        this.registry = registry;
        this.store = store;
        this.library = library;
        this.requests = new FlowRequests(registry, store, this::cancel, rec -> execute(rec, true));
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
        RunRecord rec = requests.waitingRun(runId);
        if (rec.pending.has("childRunId")) {
            accept(rec.pending.get("childRunId").asText(), answer, by);
            return;
        }
        FlowRequests.storeAnswer(rec, answer, by);
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
        RunRecord rec = requests.waitingRun(runId);
        if (rec.pending.has("childRunId")) { // waiting propagates upward: the answer belongs to the child
            resume(rec.pending.get("childRunId").asText(), answer, by, false);
        } else {
            FlowRequests.storeAnswer(rec, answer, by);
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
        return requests.request(runId, kind, payload, by);
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
        final StepRunner runner;
        final Map<String, Integer> occurrences = new LinkedHashMap<>();
        final List<Event> history = new ArrayList<>();
        int executions;
        String curStep = "";

        Run(RunRecord rec, FlowDef flow) {
            this.rec = rec;
            this.flow = flow;
            this.ctx = new FlowContext(rec.runId, rec.user, rec.input);
            this.ctx.requests(rec.requestData);
            this.runner = new StepRunner(registry, store, library, rec, flow, ctx, this::log,
                    child -> FlowEngine.this.execute(child, false));
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
                } catch (StepRunner.WaitSignal w) {
                    return waiting(w);
                } catch (StepRunner.StepFailure sf) {
                    ObjectNode err = errorNode(sf);
                    log(sf.step, "error", sf.error.kind() + ": " + sf.error.getMessage(), err);
                    ctx.error(err);
                    Finish h;
                    try {
                        h = walk(flow.handler().path("steps"), flow.handler().path("start").asText(), "h:");
                    } catch (StepRunner.WaitSignal w) {
                        return waiting(w);
                    } catch (StepRunner.StepFailure hf) {
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

        RunResult waiting(StepRunner.WaitSignal w) {
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

        ObjectNode errorNode(StepRunner.StepFailure f) {
            ObjectNode e = Json.M.createObjectNode();
            e.put("step", f.step).put("kind", f.error.kind().name()).put("message", f.error.getMessage());
            e.put("attempts", f.attempts);
            if (f.error.getCause() != null) e.put("cause", String.valueOf(f.error.getCause()));
            return e;
        }

        StepRunner.StepFailure failureFrom(JsonNode f) {
            FlowException fe = new FlowException(FlowException.Kind.valueOf(f.path("kind").asText()),
                    f.path("message").asText());
            return new StepRunner.StepFailure(f.path("step").asText(), fe, f.path("attempts").asInt());
        }

        Finish walk(JsonNode steps, String start, String prefix) {
            String cur = start;
            while (true) {
                if (++executions > MAX_STEP_EXECUTIONS) {
                    throw new StepRunner.StepFailure(cur, new FlowException(FlowException.Kind.LIMIT,
                            "More than " + MAX_STEP_EXECUTIONS + " step executions; the flow loops"), 0);
                }
                JsonNode s = steps.path(cur);
                String type = s.path("type").asText();
                if (type.equals("finish")) {
                    final String at = cur;
                    JsonNode res = runner.guard(cur, () -> runner.render(s.path("result")));
                    log(at, "finish", s.path("outcome").asText(""), res);
                    return new Finish(s.path("outcome").asText("completed"), res, s.path("retryStep").asText(null));
                }
                curStep = cur;
                int occ = occurrences.merge(prefix + cur, 1, Integer::sum);
                String key = prefix + cur + "#" + occ;
                runner.askedInStep.clear();
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
                        out = runner.runStep(at, key, s);
                    } catch (StepRunner.StepFailure sf) {
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

        String ask(String id, String key, JsonNode s) {
            List<Question.Option> opts = new ArrayList<>();
            s.path("options").fields().forEachRemaining(e -> opts.add(new Question.Option(e.getKey(), e.getValue().asText())));
            Question q = runner.guard(id, () -> FlowRequests.buildQuestion(ctx, key, s, opts));
            JsonNode a = runner.guard(id, () -> runner.answerFor(key, id, s, q));
            if (a == null) return timeoutTarget(id, s, key);
            ctx.putResult(id, a.path("value"));
            rec.results.set(key, a.path("value"));
            log(id, "answer", "by " + a.path("by").asText(), a.path("value"));
            return s.path("next").asText();
        }

        String timeoutTarget(String id, JsonNode s, String key) {
            log(id, "timeout", key, null);
            if (s.has("onTimeout")) return s.get("onTimeout").asText();
            throw new StepRunner.StepFailure(id, new FlowException(FlowException.Kind.TIMEOUT,
                    "Step '" + id + "' waited too long and has no timeout path"), 1);
        }


        String decide(String id, String key, JsonNode s) {
            JsonNode outcomes = s.path("outcomes");
            String by = s.path("by").asText();
            String outcome;
            String decidedBy = by;
            JsonNode detail = rec.decisionDetails.get(key);
            if (rec.decisions.has(key)) { // replay: never ask again
                outcome = rec.decisions.get(key).asText();
                decidedBy = rec.deciders.has(key) ? rec.deciders.get(key).asText() : "recorded";
            } else {
                String raw;
                if (by.equals("user")) {
                    List<Question.Option> opts = new ArrayList<>();
                    outcomes.fieldNames().forEachRemaining(o -> opts.add(new Question.Option(o, o)));
                    Question q = runner.guard(id, () -> FlowRequests.buildQuestion(ctx, key, withKind(s), opts));
                    JsonNode a = runner.guard(id, () -> runner.answerFor(key, id, s, q));
                    if (a == null) return timeoutTarget(id, s, key);
                    String valid = a.path("value").asText();
                    raw = valid;
                    decidedBy = "user:" + a.path("by").asText();
                } else if (by.equals("java")) {
                    Registry.Choice c = runner.guard(id, () -> runner.javaChoose(id, key, s));
                    raw = c.outcome();
                    detail = c.detail();
                } else {
                    raw = runner.guard(id, () -> runner.llmChoose(s));
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
                    throw new StepRunner.StepFailure(id, new FlowException(FlowException.Kind.CHOOSER,
                            "Chooser returned '" + bad + "', not one of " + StepRunner.names(outcomes)), 1);
                }
            }
            rec.decisions.put(key, outcome);
            if (!rec.deciders.has(key)) rec.deciders.put(key, decidedBy);
            if (detail != null && !rec.decisionDetails.has(key)) rec.decisionDetails.set(key, detail);
            ctx.addDecision(new FlowContext.Decision(id, s.path("question").asText(""), outcome, decidedBy,
                    detail == null ? Json.M.createObjectNode() : detail));
            ObjectNode d = Json.M.createObjectNode();
            d.put("outcome", outcome).put("decidedBy", decidedBy).put("target", target);
            if (detail != null) d.set("detail", detail);
            log(id, "decision", s.path("question").asText(""), d);
            return target;
        }

        JsonNode withKind(JsonNode s) {
            ObjectNode c = s.deepCopy();
            c.put("kind", "single");
            return c;
        }

    }

    /** Convenience for tests and callers: the history as one line per event. */
    public static List<String> lines(RunResult r) {
        List<String> out = new ArrayList<>();
        for (Event e : r.history()) out.add(e.step() + ":" + e.kind() + (e.detail().isEmpty() ? "" : " " + e.detail()));
        return out;
    }
}
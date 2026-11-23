package org.kendar.llm.workflows;

import org.kendar.llm.harness.workflows.WorkflowApi.QuestionKind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Executes the steps of one run: actions, child flows, tasks (model calls with clarification and tool approval),
 * retries, and the choosers of decisions. Whatever has to stop the walk throws {@link WaitSignal}.
 */
final class StepRunner {
    /** Receives the progress lines of the run. */
    @FunctionalInterface
    interface Logger {
        void log(String step, String kind, String detail, JsonNode data);
    }

    /** A step that failed after its own retries; carries what the handler gets to see. */
    static final class StepFailure extends RuntimeException {
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
    private final RunRecord rec;
    private final FlowDef flow;
    private final FlowContext ctx;
    private final Logger logger;
    private final Consumer<RunRecord> childRunner;
    final Set<String> askedInStep = new HashSet<>();

    StepRunner(Registry registry, RunStore store, FlowLibrary library, RunRecord rec, FlowDef flow, FlowContext ctx,
               Logger logger, Consumer<RunRecord> childRunner) {
        this.registry = registry;
        this.store = store;
        this.library = library;
        this.rec = rec;
        this.flow = flow;
        this.ctx = ctx;
        this.logger = logger;
        this.childRunner = childRunner;
    }

    private void log(String step, String kind, String detail, JsonNode data) {
        logger.log(step, kind, detail, data);
    }

    /** Runs a step with its retries; the result is journaled by the caller. */
    JsonNode runStep(String id, String key, JsonNode step) {
        return withRetries(id, key, step);
    }

    <T> T guard(String step, java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (FlowException e) {
            throw new StepFailure(step, e, 1);
        }
    }

    static JsonNode render(FlowContext ctx, JsonNode template) {
        if (template.isMissingNode()) return Json.M.createObjectNode();
        if (template.isTextual()) return Json.M.getNodeFactory().textNode(ctx.render(template.asText()));
        if (template.isObject()) {
            ObjectNode o = Json.M.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = template.fields(); it.hasNext(); ) {
                var e = it.next();
                o.set(e.getKey(), render(ctx, e.getValue()));
            }
            return o;
        }
        return template;
    }

    JsonNode render(JsonNode template) {
        return render(ctx, template);
    }

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
        return new Registry.ActionCall(ctx.runId(), id, ctx, taskResult, render(step.path("with")), asker);
    }

    JsonNode callAction(String name, String id, String key, JsonNode step, JsonNode taskResult) {
        Registry.FlowAction a = registry.action(name);
        if (a == null) throw new FlowException(FlowException.Kind.PERMISSION, "Action '" + name + "' is not available");
        try {
            JsonNode r = a.run(call(id, key, step, taskResult));
            return r == null ? Json.M.nullNode() : r;
        } catch (FlowException | WaitSignal e) {
            throw e;
        } catch (Exception e) { // boundary: action boundary: any failure is a FlowException
            throw new FlowException(FlowException.Kind.ACTION, "Action '" + name + "' failed: " + e.getMessage(), e);
        }
    }

    JsonNode callChild(String id, String key, JsonNode step, String childKey) {
        String name = step.path("flow").asText(null);
        String scope = step.path("scope").asText(FlowLibrary.PERSONAL);
        if (!rec.children.has(childKey)) {
            if (name == null) { // a Java chooser may name the flow, but only from the declared set
                String picked = javaChoose(id, key, step).outcome();
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
            childRunner.accept(child);
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
                String r = registry.classifier().classify(CLASSIFY_SYSTEM, "Reply:\n" + (raw.length() > 1500 ? raw.substring(raw.length() - 1500) : raw)).strip().toLowerCase();
                v = r.startsWith("ask") ? "ask" : r.startsWith("proceed") ? "proceed" : "none";
            } catch (Exception e) { // boundary: a failing classifier never blocks the flow
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
            } catch (Exception e) { // boundary: model boundary: any failure is a FlowException
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

    /** The chooser's outcome, with its explanation when it gives one. */
    Registry.Choice javaChoose(String id, String key, JsonNode s) {
        Registry.Chooser c = registry.chooser(s.path("chooser").asText());
        if (c == null) throw new FlowException(FlowException.Kind.PERMISSION, "Chooser is not available");
        try {
            if (c instanceof Registry.ExplainingChooser e) return e.explain(call(id, key, s, null));
            return new Registry.Choice(c.choose(call(id, key, s, null)), null);
        } catch (FlowException | WaitSignal e) {
            throw e;
        } catch (Exception e) { // boundary: chooser boundary: any failure is a FlowException
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
        } catch (Exception e) { // boundary: chooser boundary: any failure is a FlowException
            throw new FlowException(FlowException.Kind.CHOOSER, "Model chooser failed: " + e.getMessage(), e);
        }
    }

    List<String> names(JsonNode outcomes) {
        List<String> l = new ArrayList<>();
        outcomes.fieldNames().forEachRemaining(l::add);
        return l;
    }
}

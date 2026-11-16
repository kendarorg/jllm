package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Java actions, Java choosers and the model gateway, all looked up by registered name only. */
public final class Registry {

    /** What an action sees: the whole context, plus run and step identity for idempotence. */
    public record ActionCall(String runId, String stepId, FlowContext context, JsonNode taskResult, Asker asker) {
        /** Asks a person; returns the recorded answer at once, or pauses the run and runs the action again later. */
        public JsonNode ask(Question q) {
            return asker.ask(q);
        }

        public String askText(String key, String text) {
            return ask(Question.text(key, text)).asText();
        }

        public String askSingle(String key, String text, java.util.List<Question.Option> options) {
            return ask(Question.single(key, text, options)).asText();
        }

        public JsonNode askMulti(String key, String text, java.util.List<Question.Option> options, int min, int max) {
            return ask(Question.multi(key, text, options, min, max));
        }

        /** Stable key for side effects: the same after a crash and resume. */
        public String idempotencyKey() {
            return runId + "/" + stepId;
        }
    }

    /** Engine side of {@link ActionCall#ask}. */
    @FunctionalInterface
    public interface Asker {
        JsonNode ask(Question q);
    }

    @FunctionalInterface
    public interface FlowAction {
        /** Returns the step result; throwing an exception raises a flow error. */
        JsonNode run(ActionCall call) throws Exception;
    }

    @FunctionalInterface
    public interface Chooser {
        /** Must return one of the declared outcomes. */
        String choose(ActionCall call) throws Exception;
    }

    /** The model, seen as a plain function; the host supplies the real one (agents, tools, permissions). */
    @FunctionalInterface
    public interface ModelGateway {
        /** @throws ApprovalNeededException when a tool call needs the user's approval and was not run */
        String complete(ModelCall call) throws Exception;
    }

    /**
     * @param owner    the user the run belongs to
     * @param title    names the chat the host keeps for the tasks of one run
     * @param approved tools the user has already allowed for this run
     * @param tools    tools the task may use: null = the agent's own, empty = none, {@code ["*"]} = all of the user's role
     */
    public record ModelCall(String agent, String system, String user, String owner, String title,
                            java.util.Set<String> approved, java.util.List<String> tools) {
    }

    /** The agent behind a task step wanted to call tools that need an approval; none of them ran. */
    public static final class ApprovalNeededException extends RuntimeException {
        private final java.util.List<String> tools;

        public ApprovalNeededException(String message, java.util.List<String> tools) {
            super(message);
            this.tools = java.util.List.copyOf(tools);
        }

        public java.util.List<String> tools() {
            return tools;
        }
    }

    private final Map<String, FlowAction> actions = new ConcurrentHashMap<>();
    private final Map<String, Chooser> choosers = new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<String>> actionRoles = new ConcurrentHashMap<>();
    private volatile ModelGateway model;

    /** Limits an action to the given roles; an action never restricted is allowed for everyone. */
    public Registry restrict(String action, String... roles) {
        actionRoles.put(action, java.util.Set.of(roles));
        return this;
    }

    /** @return the roles allowed to use the action, or empty when it is allowed for all */
    public java.util.Set<String> rolesOf(String action) {
        return actionRoles.getOrDefault(action, java.util.Set.of());
    }

    private final java.util.Set<String> askingActions = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> askingChoosers = ConcurrentHashMap.newKeySet();

    /** Declares that the action may ask people questions, so it cannot be used in an unattended flow. */
    public Registry asking(String action) {
        askingActions.add(action);
        return this;
    }

    /** Declares that the chooser may ask people questions. */
    public Registry askingChooser(String chooser) {
        askingChoosers.add(chooser);
        return this;
    }

    /** Names of the registered actions, sorted. */
    public java.util.List<String> actionNames() {
        return actions.keySet().stream().sorted().toList();
    }

    /** Names of the registered choosers, sorted. */
    public java.util.List<String> chooserNames() {
        return choosers.keySet().stream().sorted().toList();
    }

    public boolean asks(String action) {
        return askingActions.contains(action);
    }

    public boolean chooserAsks(String chooser) {
        return askingChoosers.contains(chooser);
    }

    public Registry action(String name, FlowAction a) {
        if (actions.putIfAbsent(name, a) != null) throw new IllegalStateException("Duplicate action " + name);
        return this;
    }

    public Registry chooser(String name, Chooser c) {
        if (choosers.putIfAbsent(name, c) != null) throw new IllegalStateException("Duplicate chooser " + name);
        return this;
    }

    public Registry model(ModelGateway m) {
        this.model = m;
        return this;
    }

    public FlowAction action(String name) {
        return actions.get(name);
    }

    public Chooser chooser(String name) {
        return choosers.get(name);
    }

    public ModelGateway model() {
        return model;
    }
}

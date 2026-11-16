package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.workflows.api.WorkflowApi;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The engine's {@link WorkflowApi}: a thin JSON view over {@link Workflows}. */
public final class WorkflowApiImpl implements WorkflowApi {
    private final Workflows workflows;
    private final ConcurrentHashMap<String, Set<String>> roles;

    private WorkflowApiImpl(Workflows workflows, ConcurrentHashMap<String, Set<String>> roles) {
        this.workflows = workflows;
        this.roles = roles;
    }

    /** Builds the facade and the API together; the host's roles arrive with every call. */
    public static WorkflowApiImpl create(Registry registry, RunStore runs, FlowStore flows, String adminRole) {
        ConcurrentHashMap<String, Set<String>> roles = new ConcurrentHashMap<>();
        return new WorkflowApiImpl(new Workflows(registry, runs, flows,
                u -> roles.getOrDefault(u, Set.of()), adminRole), roles);
    }

    public Workflows workflows() {
        return workflows;
    }

    private void who(String user, Set<String> r) {
        roles.put(user, r == null ? Set.of() : Set.copyOf(r));
    }

    @Override
    public JsonNode list(String user, Set<String> r) {
        who(user, r);
        ArrayNode a = Json.M.createArrayNode();
        for (Workflows.Entry e : workflows.list(user)) {
            ObjectNode o = a.addObject().put("scope", e.scope()).put("name", e.name());
            ArrayNode rl = o.putArray("roles");
            e.roles().forEach(rl::add);
            o.put("canEdit", e.canEdit()).put("sharedWithYou", e.sharedWithYou());
        }
        return a;
    }

    @Override
    public JsonNode validate(String user, Set<String> r, String scope, JsonNode flow) {
        who(user, r);
        ObjectNode o = Json.M.createObjectNode();
        ArrayNode errs = o.putArray("errors");
        try {
            workflows.validate(user, scope, FlowDef.parse(flow.toString())).forEach(errs::add);
        } catch (FlowException e) {
            errs.add(e.getMessage());
        }
        return o;
    }

    @Override
    public JsonNode catalog(String user, Set<String> r) {
        who(user, r);
        Registry reg = workflows.registry();
        ObjectNode o = Json.M.createObjectNode();
        ArrayNode actions = o.putArray("actions");
        for (String n : reg.actionNames()) {
            ObjectNode a = actions.addObject().put("name", n).put("asks", reg.asks(n));
            ArrayNode rl = a.putArray("roles");
            reg.rolesOf(n).stream().sorted().forEach(rl::add);
        }
        ArrayNode choosers = o.putArray("choosers");
        for (String n : reg.chooserNames()) choosers.addObject().put("name", n).put("asks", reg.chooserAsks(n));
        o.put("canManageGlobal", workflows.isAdmin(user));
        return o;
    }

    @Override
    public JsonNode flow(String user, Set<String> r, String name, String scope) {
        who(user, r);
        return workflows.resolve(user, name, scope).doc();
    }

    @Override
    public List<String> inputs(String user, Set<String> r, String name, String scope) {
        who(user, r);
        return List.copyOf(Workflows.inputs(workflows.resolve(user, name, scope)));
    }

    @Override
    public void save(String user, Set<String> r, String scope, JsonNode flow) {
        who(user, r);
        workflows.save(user, scope, FlowDef.parse(flow.toString()));
    }

    @Override
    public void delete(String user, Set<String> r, String scope, String name) {
        who(user, r);
        workflows.delete(user, scope, name);
    }

    @Override
    public JsonNode copy(String user, Set<String> r, String name, String newName) {
        who(user, r);
        return workflows.copyToPersonal(user, name, newName).doc();
    }

    @Override
    public JsonNode start(String user, Set<String> r, String name, String scope, JsonNode input, JsonNode chatHistory) {
        who(user, r);
        ObjectNode in = input != null && input.isObject() ? ((ObjectNode) input).deepCopy() : Json.M.createObjectNode();
        if (chatHistory != null) in.set("chat", chatHistory); // optional input; never part of the flow history
        return view(workflows.run(user, workflows.start(user, name, scope, in).runId()));
    }

    @Override
    public JsonNode prepareStart(String user, Set<String> r, String name, String scope, JsonNode input) {
        who(user, r);
        ObjectNode in = input != null && input.isObject() ? ((ObjectNode) input).deepCopy() : Json.M.createObjectNode();
        return view(workflows.run(user, workflows.prepare(user, name, scope, in)));
    }

    @Override
    public void prepareAnswer(String user, Set<String> r, String runId, JsonNode answer) {
        who(user, r);
        workflows.accept(user, runId, answer);
    }

    @Override
    public JsonNode proceed(String user, Set<String> r, String runId) {
        who(user, r);
        workflows.proceed(user, runId);
        return view(workflows.run(user, runId));
    }

    @Override
    public JsonNode answer(String user, Set<String> r, String runId, JsonNode answer) {
        who(user, r);
        workflows.answer(user, runId, answer);
        return view(workflows.run(user, runId));
    }

    @Override
    public JsonNode request(String user, Set<String> r, String runId, String kind, JsonNode payload) {
        who(user, r);
        FlowEngine.RequestOutcome o = workflows.request(user, runId, kind, payload);
        ObjectNode v = view(workflows.run(user, runId));
        v.put("handledBy", String.valueOf(o.handledBy())).put("action", String.valueOf(o.action()));
        if (o.answer() != null) v.set("answer", o.answer());
        return v;
    }

    @Override
    public void cancel(String user, Set<String> r, String runId) {
        who(user, r);
        workflows.cancel(user, runId);
    }

    @Override
    public JsonNode runs(String user, Set<String> r) {
        who(user, r);
        ArrayNode a = Json.M.createArrayNode();
        workflows.runs(user).forEach(x -> a.add(view(x)));
        return a;
    }

    @Override
    public JsonNode waiting(String user, Set<String> r) {
        who(user, r);
        ArrayNode a = Json.M.createArrayNode();
        workflows.waiting(user).forEach(x -> a.add(view(x)));
        return a;
    }

    @Override
    public JsonNode run(String user, Set<String> r, String runId) {
        who(user, r);
        return view(workflows.run(user, runId));
    }

    @Override
    public JsonNode history(String user, Set<String> r, String runId) {
        who(user, r);
        return workflows.history(user, runId);
    }

    static ObjectNode view(RunRecord rec) {
        ObjectNode o = Json.M.createObjectNode();
        o.put("runId", rec.runId).put("status", rec.status).put("flow", rec.flow.path("name").asText(""));
        o.put("user", rec.user);
        o.put("startedAt", rec.startedAt).put("endedAt", rec.endedAt);
        if (rec.pending != null) o.set("pending", rec.pending);
        if (rec.result != null) o.set("result", rec.result);
        if (rec.error != null) o.set("error", rec.error);
        if (rec.parentRunId != null) o.put("parentRunId", rec.parentRunId);
        return o;
    }
}

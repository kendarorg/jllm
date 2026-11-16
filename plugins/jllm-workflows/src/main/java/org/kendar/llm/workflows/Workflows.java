package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The API front ends use (command line, chat, web): flow ownership and start, list, answer, request, cancel with
 * user isolation. Personal flows are seen only by their owner, global flows by everybody their {@code roles} allow,
 * and a run only by the user who started it. A global flow runs as the starting user.
 */
public final class Workflows {
    /**
     * One line of a flow listing.
     *
     * @param roles      the roles a global flow is shared with (empty: everybody)
     * @param canEdit    whether the user may change or delete it
     * @param sharedWithYou false when an administrator sees a global flow only because administrators see everything
     */
    public record Entry(String scope, String name, List<String> roles, boolean canEdit, boolean sharedWithYou) {
    }

    private final FlowEngine engine;
    private final FlowStore flows;
    private final Registry registry;
    private final Function<String, Set<String>> rolesOf;
    private final String adminRole;
    private final FlowLibrary library = new StoreLibrary();

    /**
     * @param rolesOf   the roles of a user, from the host
     * @param adminRole the role that may manage global flows
     */
    public Workflows(Registry registry, RunStore runs, FlowStore flows, Function<String, Set<String>> rolesOf,
                     String adminRole) {
        this.registry = registry;
        this.flows = flows;
        this.rolesOf = rolesOf;
        this.adminRole = adminRole;
        this.engine = new FlowEngine(registry, runs, library);
    }

    public FlowEngine engine() {
        return engine;
    }

    /** Library over the store; applies scope separation and role restrictions. */
    private final class StoreLibrary implements FlowLibrary {
        @Override
        public Optional<FlowDef> find(String scope, String name, String user) {
            return GLOBAL.equals(scope) ? flows.get(GLOBAL, null, name).filter(f -> mayRun(f, user))
                    : flows.get(PERSONAL, user, name);
        }
    }

    public boolean isAdmin(String user) {
        return rolesOf.apply(user).contains(adminRole);
    }

    /** Administrators see every global flow, whatever its roles. */
    private boolean mayRun(FlowDef f, String user) {
        return isAdmin(user) || sharedWith(f, user);
    }

    /** A global flow without {@code roles} is for everybody. */
    private boolean sharedWith(FlowDef f, String user) {
        JsonNode r = f.doc().path("roles");
        if (!r.isArray() || r.isEmpty()) return true;
        Set<String> mine = rolesOf.apply(user);
        for (JsonNode n : r) if (mine.contains(n.asText())) return true;
        return false;
    }

    private static List<String> rolesField(FlowDef f) {
        List<String> out = new ArrayList<>();
        if (f.doc().path("roles").isArray()) f.doc().path("roles").forEach(n -> out.add(n.asText()));
        return out;
    }

    public List<Entry> list(String user) {
        boolean admin = isAdmin(user);
        List<Entry> out = new ArrayList<>();
        for (FlowDef f : flows.listGlobal()) {
            if (mayRun(f, user)) out.add(new Entry(FlowLibrary.GLOBAL, f.name(), rolesField(f), admin, sharedWith(f, user)));
        }
        for (FlowDef f : flows.listPersonal(user)) out.add(new Entry(FlowLibrary.PERSONAL, f.name(), List.of(), true, true));
        return out;
    }

    /** When the name exists in both scopes the caller must say which one. */
    public FlowDef resolve(String user, String name, String scope) {
        if (scope != null) {
            return library.find(scope, name, user).orElseThrow(() -> new FlowException(
                    FlowException.Kind.PERMISSION, "Flow '" + name + "' (" + scope + ") does not exist or is not allowed"));
        }
        Optional<FlowDef> g = library.find(FlowLibrary.GLOBAL, name, user);
        Optional<FlowDef> p = library.find(FlowLibrary.PERSONAL, name, user);
        if (g.isPresent() && p.isPresent()) {
            throw new FlowException(FlowException.Kind.INVALID_FLOW,
                    "Flow '" + name + "' exists as global and personal; state the scope");
        }
        return g.or(() -> p).orElseThrow(() -> new FlowException(FlowException.Kind.PERMISSION,
                "Flow '" + name + "' does not exist or is not allowed"));
    }

    /** Saves a flow in place after the save-time checks. Global flows need the admin role. */
    public void save(String user, String scope, FlowDef flow) {
        if (FlowLibrary.GLOBAL.equals(scope) && !isAdmin(user)) {
            throw new FlowException(FlowException.Kind.PERMISSION, "Only administrators manage global flows");
        }
        ObjectNode doc = flow.doc().deepCopy();
        doc.put("scope", scope);
        FlowDef f = FlowDef.parse(doc.toString());
        List<String> problems = problems(user, scope, f);
        if (!problems.isEmpty()) {
            throw new FlowException(FlowException.Kind.INVALID_FLOW, "Flow '" + f.name() + "' is invalid: " + problems);
        }
        flows.save(scope, user, f);
    }

    /** The save-time checks without saving; the same list a save would refuse with. */
    public List<String> validate(String user, String scope, FlowDef flow) {
        ObjectNode doc = flow.doc().deepCopy();
        doc.put("scope", scope);
        List<String> problems = problems(user, scope, FlowDef.parse(doc.toString()));
        if (FlowLibrary.GLOBAL.equals(scope) && !isAdmin(user)) problems.add(0, "Only administrators manage global flows");
        return problems;
    }

    private List<String> problems(String user, String scope, FlowDef f) {
        List<String> problems = new ArrayList<>(FlowValidator.check(f, registry));
        if (FlowLibrary.GLOBAL.equals(scope)) problems.addAll(checkActionRoles(f));
        else if (f.doc().has("roles")) problems.add("'roles' is only for global flows");
        problems.addAll(FlowLibrary.checkChildren(f, library, user));
        return problems;
    }

    public Registry registry() {
        return registry;
    }

    public void delete(String user, String scope, String name) {
        if (FlowLibrary.GLOBAL.equals(scope) && !isAdmin(user)) {
            throw new FlowException(FlowException.Kind.PERMISSION, "Only administrators manage global flows");
        }
        flows.delete(scope, user, name);
    }

    /** Copies a global flow into the user's space; independent from then on. */
    public FlowDef copyToPersonal(String user, String name, String newName) {
        FlowDef g = resolve(user, name, FlowLibrary.GLOBAL);
        ObjectNode doc = g.doc().deepCopy();
        doc.put("name", newName == null ? name : newName);
        doc.put("scope", FlowLibrary.PERSONAL);
        doc.remove("roles");
        FlowDef c = FlowDef.parse(doc.toString());
        save(user, FlowLibrary.PERSONAL, c);
        return c;
    }

    /** Every action a global flow references must be allowed for every user it is shared with. */
    private List<String> checkActionRoles(FlowDef f) {
        List<String> errs = new ArrayList<>();
        Set<String> shared = new HashSet<>();
        JsonNode r = f.doc().path("roles");
        if (r.isArray()) r.forEach(n -> shared.add(n.asText()));
        List<String> used = new ArrayList<>();
        collectActions(f.steps(), used);
        collectActions(f.handler().path("steps"), used);
        f.tasks().fields().forEachRemaining(e -> {
            for (String role : new String[]{"before", "after", "instead"}) {
                if (e.getValue().has(role)) used.add(e.getValue().get(role).asText());
            }
        });
        for (String a : used) {
            Set<String> allowed = registry.rolesOf(a);
            if (allowed.isEmpty()) continue;
            if (shared.isEmpty() || !allowed.containsAll(shared)) {
                errs.add("action '" + a + "' is limited to " + allowed + " but the flow is shared with "
                        + (shared.isEmpty() ? "everybody" : shared.toString()));
            }
        }
        return errs;
    }

    private static void collectActions(JsonNode steps, List<String> out) {
        for (Iterator<Map.Entry<String, JsonNode>> it = steps.fields(); it.hasNext(); ) {
            JsonNode s = it.next().getValue();
            if ("action".equals(s.path("type").asText())) out.add(s.path("action").asText());
        }
    }

    // ---- runs: always the starter's

    public FlowEngine.RunResult start(String user, String name, String scope, JsonNode input) {
        return engine.run(resolve(user, name, scope), input, user);
    }

    /** Stores a new run without walking it; {@link #proceed} walks it. Returns the run id. */
    public String prepare(String user, String name, String scope, JsonNode input) {
        return engine.prepare(resolve(user, name, scope), input, user, Map.of()).runId;
    }

    /** Records the answer of a waiting run without walking it; {@link #proceed} walks it. */
    public void accept(String user, String runId, JsonNode answer) {
        run(user, runId);
        engine.accept(runId, answer, user);
    }

    public void proceed(String user, String runId) {
        run(user, runId);
        engine.proceed(runId);
    }

    public List<RunRecord> runs(String user) {
        return engine.store().list(user);
    }

    public List<RunRecord> waiting(String user) {
        return engine.store().waiting(user);
    }

    /** Another user's run looks exactly like a run that does not exist. */
    public RunRecord run(String user, String runId) {
        return engine.store().load(runId).filter(r -> r.user.equals(user)).orElseThrow(() ->
                new FlowException(FlowException.Kind.INVALID_FLOW, "Unknown run " + runId));
    }

    public FlowEngine.RunResult answer(String user, String runId, JsonNode answer) {
        run(user, runId);
        return engine.resume(runId, answer, user);
    }

    public FlowEngine.RequestOutcome request(String user, String runId, String kind, JsonNode payload) {
        run(user, runId);
        return engine.request(runId, kind, payload, user);
    }

    public void cancel(String user, String runId) {
        run(user, runId);
        engine.cancel(runId);
    }

    /** The history of a run, as stored. */
    public ArrayNode history(String user, String runId) {
        return run(user, runId).history;
    }

    /** Names of the {@code {{input.x}}} slots a flow expects. */
    public static Set<String> inputs(FlowDef f) {
        Set<String> out = new java.util.TreeSet<>();
        var m = java.util.regex.Pattern.compile("\\{\\{\\s*input\\.([A-Za-z0-9_]+)").matcher(f.doc().toString());
        while (m.find()) out.add(m.group(1));
        return out;
    }
}

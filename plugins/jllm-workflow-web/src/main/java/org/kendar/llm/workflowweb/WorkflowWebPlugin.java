package org.kendar.llm.workflowweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.plugin.WebContext;
import org.kendar.llm.harness.plugin.WebPlugin;
import org.kendar.llm.harness.plugin.WebRequest;
import org.kendar.llm.harness.plugin.WebResponse;
import org.kendar.llm.harness.workflows.WorkflowApi;

/**
 * The web API of the flows plugin: endpoints only, no screens. All logic (running, choosing, storing) is the engine's,
 * reached through {@link WorkflowApi}; this module maps HTTP to it and pushes live events.
 *
 * <pre>
 * GET    /flows                         flows the user may run, both scopes ({scope,name,roles,canEdit,sharedWithYou})
 * GET    /catalog                       registered actions and choosers, canManageGlobal
 * POST   /flows/validate                {scope, flow}: the save-time problems, nothing stored
 * GET    /flows/{scope}/{name}          definition        GET .../inputs   expected inputs
 * PUT    /flows/{scope}                 save (body = flow; global needs admin)
 * DELETE /flows/{scope}/{name}
 * POST   /flows/{name}/copy             copy a global flow to the user ({newName})
 * POST   /runs                          {name, scope?, input?}
 * GET    /runs, /runs/{id}, /runs/{id}/history
 * POST   /runs/{id}/answer              {answer}
 * POST   /runs/{id}/requests            {kind, payload}
 * POST   /runs/{id}/cancel
 * GET    /waiting, /waiting/count       what waits for the user
 * GET    events (host)                  SSE: "run" {runId,status}, "waiting" {count}
 * </pre>
 */
public final class WorkflowWebPlugin implements WebPlugin {
    public static final String ID = "jllm-workflow-web";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void install(WebContext ctx) {
        WorkflowApi api = ctx.services().lookup(WorkflowApi.class).orElseThrow(() -> new IllegalStateException(
                "needs the jllm-workflows plugin, which is missing or disabled"));
        var events = ctx.events();

        route(ctx, "GET", "/flows", r -> ok(api.list(r.user(), r.roles())));
        route(ctx, "GET", "/catalog", r -> ok(api.catalog(r.user(), r.roles())));
        route(ctx, "POST", "/flows/validate", r -> {
            JsonNode b = need(r);
            if (!b.path("flow").isObject()) throw new IllegalArgumentException("'flow' must be an object");
            String scope = text(b, "scope");
            return ok(api.validate(r.user(), r.roles(), scope == null ? "personal" : scope, b.get("flow")));
        });
        route(ctx, "GET", "/flows/{scope}/{name}", r -> ok(api.flow(r.user(), r.roles(), r.param("name"), r.param("scope"))));
        route(ctx, "GET", "/flows/{scope}/{name}/inputs", r -> {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            api.inputs(r.user(), r.roles(), r.param("name"), r.param("scope")).forEach(a::add);
            return ok(a);
        });
        route(ctx, "PUT", "/flows/{scope}", r -> {
            api.save(r.user(), r.roles(), r.param("scope"), need(r));
            return ok(obj().put("saved", true));
        });
        route(ctx, "DELETE", "/flows/{scope}/{name}", r -> {
            api.delete(r.user(), r.roles(), r.param("scope"), r.param("name"));
            return ok(obj().put("deleted", true));
        });
        route(ctx, "POST", "/flows/{name}/copy", r -> ok(api.copy(r.user(), r.roles(), r.param("name"),
                text(r.body(), "newName"))));

        route(ctx, "POST", "/runs", r -> {
            JsonNode b = need(r);
            JsonNode run = api.prepareStart(r.user(), r.roles(), text(b, "name"), text(b, "scope"),
                    b.path("input").isMissingNode() ? null : b.get("input"));
            changed(api, events, r.user(), r.roles(), run);
            background(api, events, r.user(), r.roles(), run.path("runId").asText());
            return ok(run);
        });
        route(ctx, "GET", "/runs", r -> ok(api.runs(r.user(), r.roles())));
        route(ctx, "GET", "/runs/{id}", r -> ok(api.run(r.user(), r.roles(), r.param("id"))));
        route(ctx, "GET", "/runs/{id}/history", r -> ok(api.history(r.user(), r.roles(), r.param("id"))));
        route(ctx, "POST", "/runs/{id}/answer", r -> {
            api.prepareAnswer(r.user(), r.roles(), r.param("id"), need(r).path("answer"));
            JsonNode run = api.run(r.user(), r.roles(), r.param("id"));
            changed(api, events, r.user(), r.roles(), run);
            background(api, events, r.user(), r.roles(), r.param("id"));
            return ok(run);
        });
        route(ctx, "POST", "/runs/{id}/requests", r -> {
            JsonNode b = need(r);
            JsonNode run = api.request(r.user(), r.roles(), r.param("id"), text(b, "kind"), b.path("payload"));
            changed(api, events, r.user(), r.roles(), run);
            return ok(run);
        });
        route(ctx, "POST", "/runs/{id}/cancel", r -> {
            api.cancel(r.user(), r.roles(), r.param("id"));
            JsonNode run = api.run(r.user(), r.roles(), r.param("id"));
            changed(api, events, r.user(), r.roles(), run);
            return ok(run);
        });
        route(ctx, "GET", "/waiting", r -> ok(api.waiting(r.user(), r.roles())));
        route(ctx, "GET", "/waiting/count", r -> ok(obj().put("count", api.waiting(r.user(), r.roles()).size())));
    }

    /** A run changed: tell the user's open streams, with the new number of questions waiting. */
    private static void changed(WorkflowApi api, org.kendar.llm.harness.plugin.PluginEvents events, String user,
                                java.util.Set<String> roles, JsonNode run) {
        events.push(user, "run", obj().put("runId", run.path("runId").asText())
                .put("status", run.path("status").asText()));
        events.push(user, "waiting", obj().put("count", api.waiting(user, roles).size()));
    }

    /** Runs are walked here, aside from the request and chat threads; the user's streams hear when it ends. */
    private static final java.util.concurrent.ExecutorService RUNNER = java.util.concurrent.Executors.newCachedThreadPool(rn -> {
        Thread t = new Thread(rn, "flow-runner");
        t.setDaemon(true);
        return t;
    });

    private static void background(WorkflowApi api, org.kendar.llm.harness.plugin.PluginEvents events, String user,
                                    java.util.Set<String> roles, String runId) {
        java.util.Set<String> rl = java.util.Set.copyOf(roles);
        RUNNER.execute(() -> {
            try {
                changed(api, events, user, rl, api.proceed(user, rl, runId));
            } catch (RuntimeException e) { // boundary: flow thread top level: the run is marked failed
                System.getLogger(ID).log(System.Logger.Level.ERROR, "flow run " + runId + " failed: " + e, e);
                try { changed(api, events, user, rl, api.run(user, rl, runId)); } catch (RuntimeException ignored) { /* boundary: the run is gone */ }
            }
        });
    }

    private static ObjectNode obj() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static WebResponse ok(JsonNode n) {
        return WebResponse.ok(n);
    }

    private static JsonNode need(WebRequest r) {
        if (r.body() == null || !r.body().isObject()) throw new IllegalArgumentException("A JSON object body is needed");
        return r.body();
    }

    private static String text(JsonNode b, String field) {
        JsonNode n = b.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    /** Engine errors (refused answer, not allowed, unknown run) become 4xx, not server errors. */
    private static void route(WebContext ctx, String method, String pattern, org.kendar.llm.harness.plugin.WebHandler h) {
        ctx.route(method, pattern, r -> {
            try {
                return h.handle(r);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return WebResponse.error(400, e.getMessage());
            } catch (RuntimeException e) { // boundary: plugin boundary: mapped to an HTTP status
                String m = String.valueOf(e.getMessage());
                return WebResponse.error(m.startsWith("Unknown run") ? 404 : m.contains("not allowed")
                        || m.contains("Only administrators") ? 403 : 400, m);
            }
        });
    }
}

package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake REST API that serves its own OpenAPI 3 spec at {@code /openapi.json} (generated from the routes), so the
 * harness can discover it with {@code openApiUrl}. Routes are lambdas over path parameters and the request body.
 */
public final class FakeRestServer extends FakeServer {

    private static final ObjectMapper M = new ObjectMapper();

    /** The answer of a route. */
    public record Response(int status, String body) {
        public static Response ok(String json) {
            return new Response(200, json);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Map<String, String> pathParams, String body);
    }

    private record Route(String method, String template, String operationId, String summary, Pattern pattern,
                         List<String> params, Handler handler) {
    }

    private final List<Route> routes = new ArrayList<>();

    public FakeRestServer(String name) {
        super(name);
    }

    /** Adds an operation, e.g. {@code route("GET", "/orders/{id}", "getOrder", "Returns one order", handler)}. */
    public FakeRestServer route(String method, String template, String operationId, String summary, Handler h) {
        List<String> params = new ArrayList<>();
        Matcher m = Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder rx = new StringBuilder();
        while (m.find()) {
            params.add(m.group(1));
            m.appendReplacement(rx, "([^/]+)");
        }
        m.appendTail(rx);
        routes.add(new Route(method, template, operationId, summary, Pattern.compile(rx.toString()), params, h));
        return this;
    }

    /**
     * Orders API: {@code listOrders} (GET /orders), {@code getOrder} (GET /orders/{id}), {@code createOrder}
     * (POST /orders) and {@code deleteOrder} (DELETE /orders/{id}). Orders A-1 and A-2 exist.
     */
    public static FakeRestServer sample(String name) {
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("A-1", "{\"id\":\"A-1\",\"item\":\"keyboard\",\"status\":\"shipped\"}");
        orders.put("A-2", "{\"id\":\"A-2\",\"item\":\"monitor\",\"status\":\"processing\"}");
        return new FakeRestServer(name)
                .route("GET", "/orders", "listOrders", "Lists all orders",
                        (p, b) -> Response.ok("[" + String.join(",", orders.values()) + "]"))
                .route("GET", "/orders/{id}", "getOrder", "Returns one order",
                        (p, b) -> orders.containsKey(p.get("id")) ? Response.ok(orders.get(p.get("id")))
                                : new Response(404, "{\"error\":\"order not found\"}"))
                .route("POST", "/orders", "createOrder", "Creates an order from a JSON body with an item",
                        (p, b) -> {
                            String id = "A-" + (orders.size() + 1);
                            orders.put(id, "{\"id\":\"" + id + "\",\"item\":\"new\",\"status\":\"processing\"}");
                            return new Response(201, orders.get(id));
                        })
                .route("DELETE", "/orders/{id}", "deleteOrder", "Deletes an order",
                        (p, b) -> orders.remove(p.get("id")) != null ? Response.ok("{\"deleted\":true}")
                                : new Response(404, "{\"error\":\"order not found\"}"));
    }

    @Override
    protected Reply handle(String method, String path, String query, String body) {
        if ("GET".equals(method) && path.equals("/openapi.json")) {
            return Reply.json(200, spec().toString(), "protocol", "openapi");
        }
        for (Route r : routes) {
            if (!r.method().equals(method)) continue;
            Matcher m = r.pattern().matcher(path);
            if (!m.matches()) continue;
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < r.params().size(); i++) params.put(r.params().get(i), m.group(i + 1));
            Response resp = r.handler().handle(params, body);
            return Reply.json(resp.status(), resp.body(), "call", r.operationId());
        }
        return Reply.json(404, "{\"error\":\"no such route\"}", "protocol", "not-found");
    }

    private ObjectNode spec() {
        ObjectNode root = M.createObjectNode();
        root.put("openapi", "3.0.3");
        root.putObject("info").put("title", name()).put("version", "1");
        root.putArray("servers").addObject().put("url", url());
        ObjectNode paths = root.putObject("paths");
        for (Route r : routes) {
            ObjectNode item = paths.has(r.template()) ? (ObjectNode) paths.get(r.template())
                    : paths.putObject(r.template());
            ObjectNode op = item.putObject(r.method().toLowerCase());
            op.put("operationId", r.operationId()).put("summary", r.summary());
            var ps = op.putArray("parameters");
            for (String p : r.params()) {
                ObjectNode o = ps.addObject();
                o.put("name", p).put("in", "path").put("required", true);
                o.putObject("schema").put("type", "string");
            }
            if (r.params().isEmpty()) op.remove("parameters");
            if (r.method().equals("POST") || r.method().equals("PUT")) {
                ObjectNode rb = op.putObject("requestBody");
                rb.putObject("content").putObject("application/json").putObject("schema").put("type", "object");
            }
            op.putObject("responses").putObject("200").put("description", "ok");
        }
        return root;
    }
}

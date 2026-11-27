package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    private record Route(String method, String template, String operationId, String summary,
                         Map<String, String> bodyFields, Pattern pattern, List<String> params, Handler handler) {
    }

    private final List<Route> routes = new ArrayList<>();

    public FakeRestServer(String name) {
        super(name);
    }

    /**
     * Adds an operation, e.g. {@code route("GET", "/orders/{id}", "getOrder", "Returns one order", null, handler)}.
     *
     * @param bodyFields required string fields of the JSON body (name to description), published in the spec; null
     *                   for an operation without a body
     */
    public FakeRestServer route(String method, String template, String operationId, String summary,
                                Map<String, String> bodyFields, Handler h) {
        List<String> params = new ArrayList<>();
        Matcher m = Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder rx = new StringBuilder();
        while (m.find()) {
            params.add(m.group(1));
            m.appendReplacement(rx, "([^/]+)");
        }
        m.appendTail(rx);
        routes.add(new Route(method, template, operationId, summary, bodyFields, Pattern.compile(rx.toString()),
                params, h));
        return this;
    }

    /**
     * Orders API: {@code listOrders} (GET /orders), {@code getOrder} (GET /orders/{id}), {@code createOrder}
     * (POST /orders) and {@code deleteOrder} (DELETE /orders/{id}). Orders A-1 and A-2 exist.
     */
    public static FakeRestServer sample(String name) {
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("A-1", order("A-1", "keyboard", "shipped"));
        orders.put("A-2", order("A-2", "monitor", "processing"));
        AtomicInteger next = new AtomicInteger(3); // ids are never reused, as in a real store
        return new FakeRestServer(name)
                .route("GET", "/orders", "listOrders", "Lists all orders", null,
                        (p, b) -> Response.ok("[" + String.join(",", orders.values()) + "]"))
                .route("GET", "/orders/{id}", "getOrder", "Returns one order", null,
                        (p, b) -> orders.containsKey(p.get("id")) ? Response.ok(orders.get(p.get("id")))
                                : new Response(404, "{\"error\":\"order not found\"}"))
                .route("POST", "/orders", "createOrder", "Creates an order for an item",
                        Map.of("item", "What is ordered, e.g. keyboard"),
                        (p, b) -> {
                            String item = field(b, "item");
                            if (item == null || item.isBlank()) {
                                return new Response(400, "{\"error\":\"item is required\"}");
                            }
                            String id = "A-" + next.getAndIncrement();
                            orders.put(id, order(id, item, "processing"));
                            return new Response(201, orders.get(id));
                        })
                .route("DELETE", "/orders/{id}", "deleteOrder", "Deletes an order", null,
                        (p, b) -> orders.remove(p.get("id")) != null ? Response.ok("{\"deleted\":true}")
                                : new Response(404, "{\"error\":\"order not found\"}"));
    }

    private static String order(String id, String item, String status) {
        return M.createObjectNode().put("id", id).put("item", item).put("status", status).toString();
    }

    /** A text field of a JSON body; null when the body is missing, not JSON or has no such text field. */
    private static String field(String body, String name) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = M.readTree(body).path(name);
            return n.isTextual() ? n.asText() : null;
        } catch (IOException e) {
            return null;
        }
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
                ObjectNode schema = rb.putObject("content").putObject("application/json").putObject("schema")
                        .put("type", "object");
                if (r.bodyFields() != null && !r.bodyFields().isEmpty()) {
                    rb.put("required", true);
                    ObjectNode props = schema.putObject("properties");
                    var required = schema.putArray("required");
                    r.bodyFields().forEach((f, d) -> {
                        props.putObject(f).put("type", "string").put("description", d);
                        required.add(f);
                    });
                }
            }
            op.putObject("responses").putObject("200").put("description", "ok");
        }
        return root;
    }
}

package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A flow as written in its configuration file (JSON). Thin typed view over the document; the document itself is
 * the snapshot a run keeps.
 */
public final class FlowDef {
    public static final int DEFAULT_RETRIES = 3;
    public static final int DEFAULT_QUESTIONS = 5;
    public static final int DEFAULT_DEPTH = 4;

    private final JsonNode doc;

    private FlowDef(JsonNode doc) {
        this.doc = doc;
    }

    public static FlowDef parse(String json) {
        JsonNode n = Json.parse(json);
        if (!n.isObject()) throw new FlowException(FlowException.Kind.INVALID_FLOW, "A flow must be a JSON object");
        return new FlowDef(withDefaultHandler(n.deepCopy()));
    }

    public static FlowDef load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /** Every flow ends with an error handler; a missing one becomes "record and fail". */
    private static JsonNode withDefaultHandler(JsonNode n) {
        ObjectNode o = (ObjectNode) n;
        if (!o.has("onError")) {
            ObjectNode h = o.putObject("onError");
            h.put("start", "fail");
            h.putObject("steps").putObject("fail").put("type", "finish").put("outcome", "rethrow");
        }
        return o;
    }

    /** The private copy a run keeps; later edits of the flow never touch it. */
    public FlowDef snapshot() {
        return new FlowDef(doc.deepCopy());
    }

    public JsonNode doc() {
        return doc;
    }

    public String name() {
        return doc.path("name").asText("");
    }

    /** {@code global} or {@code personal} (default). */
    public String scope() {
        return doc.path("scope").asText("personal");
    }

    public String start() {
        return doc.path("start").asText("");
    }

    public JsonNode steps() {
        return doc.path("steps");
    }

    public JsonNode tasks() {
        return doc.path("tasks");
    }

    /** Request kinds accepted at every stage that does not declare its own. */
    public JsonNode accepts() {
        return doc.path("accepts");
    }

    public JsonNode handler() {
        return doc.path("onError");
    }

    /** A step's value wins over the flow's, and the flow's over the built-in default. */
    public int limit(String key, JsonNode step, int builtIn) {
        if (step != null && step.has(key)) return step.get(key).asInt();
        JsonNode l = doc.path("limits").path(key);
        return l.isInt() ? l.asInt() : builtIn;
    }
}

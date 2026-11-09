package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default validator: the tool's {@code inputSchema()} checked with a JSON Schema validator. In
 * {@link ValidationMode#LENIENT} mode, first repairs what a model gets wrong often and harmlessly: numbers and
 * booleans sent as strings, and {@code null} for an optional field. A schema the library cannot use is skipped with a
 * warning, never turned into a failed call.
 */
public final class JsonSchemaToolInputValidator implements ToolInputValidator {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaToolInputValidator.class);

    private record Compiled(JsonNode source, JsonSchema schema) {
    }

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private final Map<String, Compiled> cache = new ConcurrentHashMap<>();

    @Override
    public Outcome validate(HarnessTool tool, JsonNode input, ValidationMode mode) {
        if (mode == ValidationMode.OFF) return Outcome.ok(input);
        JsonNode schemaNode = tool.inputSchema();
        if (schemaNode == null || schemaNode.isNull() || schemaNode.isMissingNode()) return Outcome.ok(input);
        JsonSchema schema = compile(tool.name(), schemaNode);
        if (schema == null) return Outcome.ok(input);
        JsonNode in = input == null || input.isMissingNode() || input.isNull()
                ? JsonNodeFactory.instance.objectNode() : input;
        if (mode == ValidationMode.LENIENT) in = repair(schemaNode, in.deepCopy());
        List<String> errors = new ArrayList<>();
        for (ValidationMessage m : schema.validate(in)) errors.add(m.getMessage());
        java.util.Collections.sort(errors);
        return new Outcome(in, errors);
    }

    private JsonSchema compile(String name, JsonNode schemaNode) {
        Compiled c = cache.get(name);
        if (c != null && c.source().equals(schemaNode)) return c.schema();
        try {
            JsonSchema s = factory.getSchema(schemaNode);
            cache.put(name, new Compiled(schemaNode.deepCopy(), s));
            return s;
        } catch (RuntimeException e) {
            log.warn("Input schema of tool '{}' cannot be used for validation, skipped: {}", name, e.getMessage());
            return null;
        }
    }

    /** Repairs {@code node} in place along {@code schema}; returns the (possibly replaced) node. */
    static JsonNode repair(JsonNode schema, JsonNode node) {
        if (schema == null || !schema.isObject()) return node;
        String type = schema.path("type").isTextual() ? schema.get("type").asText() : null;
        if (node.isTextual() && type != null) {
            String t = node.asText().strip();
            try {
                switch (type) {
                    case "integer" -> {
                        if (t.matches("[+-]?\\d+")) return JsonNodeFactory.instance.numberNode(Long.parseLong(t));
                    }
                    case "number" -> {
                        if (!t.isEmpty()) return JsonNodeFactory.instance.numberNode(Double.parseDouble(t));
                    }
                    case "boolean" -> {
                        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                            return JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(t));
                        }
                    }
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // left as is: the validator reports the type error
            }
            return node;
        }
        if (node.isObject() && schema.path("properties").isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode props = schema.get("properties");
            JsonNode req = schema.path("required");
            for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                JsonNode prop = props.get(f);
                if (prop == null) continue;
                JsonNode v = obj.get(f);
                if (v.isNull() && !isRequired(req, f) && !allowsNull(prop)) {
                    it.remove();
                    continue;
                }
                JsonNode fixed = repair(prop, v);
                if (fixed != v) obj.set(f, fixed);
            }
        } else if (node.isArray() && schema.path("items").isObject()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode fixed = repair(schema.get("items"), arr.get(i));
                if (fixed != arr.get(i)) arr.set(i, fixed);
            }
        }
        return node;
    }

    private static boolean isRequired(JsonNode required, String field) {
        if (!required.isArray()) return false;
        for (JsonNode r : required) if (field.equals(r.asText())) return true;
        return false;
    }

    private static boolean allowsNull(JsonNode prop) {
        JsonNode t = prop.path("type");
        if (t.isArray()) {
            for (JsonNode x : t) if ("null".equals(x.asText())) return true;
            return false;
        }
        return "null".equals(t.asText());
    }
}

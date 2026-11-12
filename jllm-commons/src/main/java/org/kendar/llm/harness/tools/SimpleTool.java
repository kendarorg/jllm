package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.BiFunction;

/** Lambda-backed {@link HarnessTool} with a compact schema builder. */
public final class SimpleTool implements HarnessTool {

    public interface Body {
        String run(ToolContext ctx, JsonNode input) throws Exception;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String description;
    private final JsonNode schema;
    private final String group;
    private final ToolKind kind;
    private final Body body;
    private final BiFunction<ToolContext, JsonNode, String> preview;
    private final TruncationMode truncation;

    public SimpleTool(String name, String description, JsonNode schema, String group, ToolKind kind, Body body) {
        this(name, description, schema, group, kind, body, null);
    }

    public SimpleTool(String name, String description, JsonNode schema, String group, ToolKind kind, Body body,
                      BiFunction<ToolContext, JsonNode, String> preview) {
        this(name, description, schema, group, kind, body, preview, TruncationMode.HEAD);
    }

    public SimpleTool(String name, String description, JsonNode schema, String group, ToolKind kind, Body body,
                      BiFunction<ToolContext, JsonNode, String> preview, TruncationMode truncation) {
        this.truncation = truncation;
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.group = group;
        this.kind = kind;
        this.body = body;
        this.preview = preview;
    }

    /**
     * Builds an object schema from alternating (name, description) pairs. A name may carry a
     * ":type" suffix (default string) and a trailing "?" marks it optional, e.g. "limit:integer?".
     */
    public static ObjectNode schema(String... namesAndDescriptions) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (int i = 0; i < namesAndDescriptions.length; i += 2) {
            String n = namesAndDescriptions[i];
            boolean optional = n.endsWith("?");
            if (optional) n = n.substring(0, n.length() - 1);
            String type = "string";
            int colon = n.indexOf(':');
            if (colon > 0) {
                type = n.substring(colon + 1);
                n = n.substring(0, colon);
            }
            ObjectNode p = props.putObject(n);
            p.put("type", type);
            p.put("description", namesAndDescriptions[i + 1]);
            if (!optional) required.add(n);
        }
        if (required.isEmpty()) root.remove("required");
        return root;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public String group() { return group; }
    @Override public ToolKind kind() { return kind; }
    @Override public TruncationMode truncation() { return truncation; }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        return body.run(ctx, input);
    }

    @Override
    public String preview(ToolContext ctx, JsonNode input) {
        return preview != null ? preview.apply(ctx, input) : HarnessTool.super.preview(ctx, input);
    }
}

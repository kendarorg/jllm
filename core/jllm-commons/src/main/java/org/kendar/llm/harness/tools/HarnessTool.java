package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool the agent loop can call. Deliberately not a Spring AI {@code ToolCallbackProvider}:
 * those beans are auto-exposed through MCP, /tools, /api/tools and OpenAPI.
 */
public interface HarnessTool {

    String name();

    String description();

    /** JSON Schema for the input object. */
    JsonNode inputSchema();

    /** Same convention as {@code @ToolGroup}, e.g. "Files", "Web", "Cgc/Search". */
    String group();

    ToolKind kind();

    /** Which end of an over-long result the generic safety cut keeps. */
    default TruncationMode truncation() {
        return TruncationMode.HEAD;
    }

    String execute(ToolContext ctx, JsonNode input) throws Exception;

    /** Human-readable preview shown in the approval dialog (e.g. a diff). */
    default String preview(ToolContext ctx, JsonNode input) {
        return input.toString();
    }
}

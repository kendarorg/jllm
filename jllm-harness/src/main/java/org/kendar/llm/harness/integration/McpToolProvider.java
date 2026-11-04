package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/** Exposes the tools of an MCP server as harness tools named {@code mcp__<server>__<tool>}. */
public final class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    private McpToolProvider() {
    }

    /** Never throws: an unreachable server is logged and contributes no tools. */
    public static List<HarnessTool> load(McpServerSettings server, HttpClient http, ObjectMapper mapper) {
        if (!server.active()) return new ArrayList<>();
        try {
            return discover(server, http, mapper);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("MCP server '{}' skipped: {}", server.name(), e.toString());
            return new ArrayList<>();
        }
    }

    /** Connects to the server and lists its tools. */
    public static List<HarnessTool> discover(McpServerSettings server, HttpClient http, ObjectMapper mapper)
            throws IOException, InterruptedException {
        List<HarnessTool> out = new ArrayList<>();
        McpHttpClient client = new McpHttpClient(http, mapper, server.url(), server.headers());
        for (JsonNode t : client.listTools()) {
            String remote = t.path("name").asText();
            JsonNode schema = t.has("inputSchema") ? t.get("inputSchema")
                    : mapper.createObjectNode().put("type", "object");
            out.add(new SimpleTool(ToolNames.of("mcp__" + server.name() + "__" + remote),
                    t.path("description").asText(remote), schema, server.effectiveGroup(),
                    server.isReadOnly() ? ToolKind.READ_ONLY : ToolKind.OTHER,
                    (ctx, input) -> render(client.callTool(remote, input))));
        }
        return out;
    }

    private static String render(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("text".equals(c.path("type").asText()) ? c.path("text").asText() : c.toString());
        }
        if (sb.isEmpty() && result.has("structuredContent")) sb.append(result.get("structuredContent"));
        return result.path("isError").asBoolean(false) ? "Error: " + sb : sb.toString();
    }
}

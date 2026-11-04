package org.kendar.llm.harness.settings;

import java.util.Map;

/**
 * An MCP server reached over HTTP.
 *
 * @param group    tool group; default {@code Mcp/<name>}
 * @param enabled  default true
 * @param readOnly when true the tools of the server do not need approval
 */
public record McpServerSettings(String name, String url, Map<String, String> headers, String group, Boolean enabled,
                                Boolean readOnly) {

    public McpServerSettings {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public boolean active() {
        return enabled == null || enabled;
    }

    public boolean isReadOnly() {
        return readOnly != null && readOnly;
    }

    public String effectiveGroup() {
        return group == null || group.isBlank() ? "Mcp/" + name : group;
    }
}

package org.kendar.llm.harness.settings;

import java.util.List;
import java.util.Map;

/**
 * An MCP server: either reached over streamable HTTP ({@code url}, with {@code headers}) or started as a child
 * process speaking JSON-RPC on stdin/stdout ({@code command}, {@code args}, {@code env}). Exactly one of
 * {@code url} and {@code command} is set.
 *
 * @param group     tool group; default {@code Mcp/<name>}
 * @param enabled   default true
 * @param readOnly  when true the tools of the server do not need approval
 * @param command   executable of a stdio server
 * @param args      arguments of the executable
 * @param env       environment variables added to the child process
 * @param oauth     HTTP server that needs an OAuth login: run {@code /login mcp:<name>} once; default false
 * @param allowList tool names (wildcard {@code *}) that are exposed. Absent allows all, empty allows none,
 *                  {@code ["*"]} all
 * @param denyList  tool names (wildcard {@code *}) that are hidden. Empty or absent hides nothing, {@code ["*"]}
 *                  hides everything
 */
public record McpServerSettings(String name, String url, Map<String, String> headers, String group, Boolean enabled,
                                Boolean readOnly, String command, List<String> args, Map<String, String> env,
                                Boolean oauth, List<String> allowList, List<String> denyList) {

    public McpServerSettings {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        allowList = allowList == null ? null : List.copyOf(allowList);
        denyList = denyList == null ? null : List.copyOf(denyList);
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasCommand = command != null && !command.isBlank();
        if (hasUrl == hasCommand) {
            throw new IllegalArgumentException("MCP server '" + name + "' needs exactly one of url or command");
        }
    }

    public McpServerSettings(String name, String url, Map<String, String> headers, String group, Boolean enabled,
                             Boolean readOnly, String command, List<String> args, Map<String, String> env,
                             Boolean oauth) {
        this(name, url, headers, group, enabled, readOnly, command, args, env, oauth, null, null);
    }

    public McpServerSettings(String name, String url, Map<String, String> headers, String group, Boolean enabled,
                             Boolean readOnly, String command, List<String> args, Map<String, String> env) {
        this(name, url, headers, group, enabled, readOnly, command, args, env, null);
    }

    /** An HTTP server. */
    public McpServerSettings(String name, String url, Map<String, String> headers, String group, Boolean enabled,
                             Boolean readOnly) {
        this(name, url, headers, group, enabled, readOnly, null, null, null, null);
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
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

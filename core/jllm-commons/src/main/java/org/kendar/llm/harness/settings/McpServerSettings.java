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
 * @param perUser   with {@code oauth}: every user's calls carry their own token ({@code /login mcp:<name>} per user);
 *                  discovery uses the shared (admin) login. Default true when the harness has users, false for a
 *                  single user (one shared login, as before). Not allowed on a stdio server
 */
public record McpServerSettings(String name, String url, Map<String, String> headers, String group, Boolean enabled,
                                Boolean readOnly, String command, List<String> args, Map<String, String> env,
                                Boolean oauth, List<String> allowList, List<String> denyList, Boolean perUser) {

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
        if (hasCommand && Boolean.TRUE.equals(perUser)) {
            throw new IllegalArgumentException("MCP server '" + name + "': perUser needs an HTTP server (a stdio server is one process for everybody)");
        }
    }

    /** Whether calls use each user's own token: {@code oauth} and {@code perUser} (default {@code multiUser}). */
    public boolean perUserOAuth(boolean multiUser) {
        return Boolean.TRUE.equals(oauth) && !isStdio() && (perUser != null ? perUser : multiUser);
    }

    /** The token store and credential source name of this server: {@code mcp:<name>}. */
    public String source() {
        return "mcp:" + name;
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

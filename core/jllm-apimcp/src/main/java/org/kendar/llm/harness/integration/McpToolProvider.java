package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.auth.AuthProvider;
import org.kendar.llm.harness.auth.Identity;
import org.kendar.llm.harness.auth.LoginRequiredException;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.util.ToolFilter;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.tools.TruncationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

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
        } catch (Exception e) { // boundary: one broken MCP server must not stop the others
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("MCP server '{}' skipped: {}", server.name(), e.toString());
            return new ArrayList<>();
        }
    }

    /**
     * A client for the server's transport, not yet connected.
     *
     * @param inheritedEnv  patterns of the process variables a stdio server inherits; null: all
     * @param oauth         builds the auth provider of a server with {@code oauth: true}; the harness supplies it (the
     *                      OAuth login lives there), so this module needs no login code; may return null
     * @param onUnavailable called once, with the reason, when the server is lost for good after it worked (an HTTP
     *                      session cannot be re-initialized, a stdio server cannot be restarted); its source should
     *                      then be disabled. Later calls of the client throw {@link McpUnavailableException}.
     *                      Runs on the thread of the failing call, outside the client's locks. Null: none.
     */
    public static McpClient connect(McpServerSettings server, HttpClient http, ObjectMapper mapper,
                                    List<String> inheritedEnv, Function<McpServerSettings, AuthProvider> oauth,
                                    Consumer<String> onUnavailable) {
        return server.isStdio()
                ? new McpStdioClient(mapper, server.name(), server.command(), server.args(), server.env(), inheritedEnv)
                        .onUnavailable(onUnavailable)
                : withOAuth(server, new McpHttpClient(http, mapper, server.url(), server.headers())
                        .onUnavailable(onUnavailable), oauth);
    }

    private static McpClient withOAuth(McpServerSettings server, McpHttpClient client,
                                       Function<McpServerSettings, AuthProvider> oauth) {
        if (!Boolean.TRUE.equals(server.oauth())) return client;
        AuthProvider provider = oauth.apply(server);
        if (provider == null) {
            throw new IllegalStateException("MCP server '" + server.name() + "' needs OAuth, which is not configured");
        }
        return client.authProvider(provider);
    }

    /** Connects to the server and lists its tools. The tools keep the client, so it stays open. */
    public static List<HarnessTool> discover(McpServerSettings server, HttpClient http, ObjectMapper mapper)
            throws IOException, InterruptedException {
        return tools(server, connect(server, http, mapper, null, x -> null, null), mapper, Identity.ANONYMOUS, null,
                TruncationMode.HEAD);
    }

    /**
     * The tools of a connected client, named {@code mcp__<server>__<tool>}; calls go through the client, each made for
     * the principal of its chat with the credentials that apply to that principal. The names are also taken in
     * {@code claims} (null: none) under the source {@code "mcp:<server>"}.
     *
     * @param truncation which end of an over-long result the harness keeps
     *
     * @throws ToolNames.CollisionException when two tools get the same name, within the server or with another
     *                                      source of {@code claims}
     */
    public static List<HarnessTool> tools(McpServerSettings server, McpClient client, ObjectMapper mapper,
                                          Identity identity, ToolNames.Claims claims,
                                          TruncationMode truncation)
            throws IOException, InterruptedException {
        List<HarnessTool> out = new ArrayList<>();
        ToolNames.SourceNames names = new ToolNames.SourceNames();
        int listed = 0;
        for (JsonNode t : client.listTools()) {
            listed++;
            String remote = t.path("name").asText();
            if (!ToolFilter.visible(server.allowList(), server.denyList(), remote)) continue;
            JsonNode schema = t.has("inputSchema") ? t.get("inputSchema")
                    : mapper.createObjectNode().put("type", "object");
            String name = names.add("mcp__" + server.name() + "__" + remote,
                    "MCP server '" + server.name() + "' tool '" + remote + "'");
            out.add(new SimpleTool(name,
                    t.path("description").asText(remote), schema, server.effectiveGroup(),
                    server.isReadOnly() ? ToolKind.READ_ONLY : ToolKind.OTHER,
                    (ctx, input) -> {
                        Identity.Caller caller = identity.callerOf(ctx == null ? null : ctx.chatId());
                        String pid = caller.principal() == null ? null : caller.principal().id();
                        String source = server.source();
                        try {
                            return render(client.callTool(remote, input, pid, caller.headers(source)));
                        } catch (McpUnauthorizedException e) {
                            if (!caller.onUnauthorized(source)) throw e;
                            try {
                                return render(client.callTool(remote, input, pid, caller.headers(source)));
                            } catch (McpUnauthorizedException again) {
                                throw new LoginRequiredException(source, again);
                            }
                        }
                    }, null, truncation));
        }
        if (out.isEmpty() && listed > 0) {
            log.warn("MCP server '{}': none of its {} tools passes allowList {} and denyList {}", server.name(),
                    listed, server.allowList(), server.denyList());
        }
        if (claims != null) claims.claimAll("mcp:" + server.name(), names.names());
        return out;
    }

    private static String render(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("text".equals(c.path("type").asText()) ? c.path("text").asText() : c.toString());
        }
        if (sb.isEmpty() && result.has("structuredContent")) sb.append(result.get("structuredContent"));
        return result.path("isError").asBoolean(false) ? "Error: " + sb : sb.toString();
    }
}

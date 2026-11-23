package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * What a project settings file may define only with the user's consent: local processes (stdio MCP servers, stop
 * hooks), OAuth blocks that decide where a token is sent, literal client secrets and the token store. Applied to the
 * merged settings tree by {@link HarnessSettingsLoader#loadLayered}; what is defined by another layer too counts as
 * the user's own and is kept.
 */
public final class ProjectTrustFilter {

    /** Name prefix under which a project REST API with oauth is asked about and remembered in {@link ProjectMcpTrust}. */
    public static final String OAUTH_TRUST_PREFIX = "oauth:api:";
    /** Name under which a project stop hook is asked about and remembered in {@link ProjectMcpTrust}. */
    public static final String STOP_HOOK_TRUST_PREFIX = "stop-hook:";

    private static final Pattern ENV_ONLY = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");

    private ProjectTrustFilter() {
    }

    /**
     * Applies every rule to {@code merged}.
     *
     * @param projectTree the project layer alone
     * @param others      the other layers
     * @throws IllegalArgumentException for a literal OAuth client secret in the project layer
     */
    static void apply(ObjectNode merged, JsonNode projectTree, List<JsonNode> others, Path projectDir,
                      ProjectMcpTrust.Prompt prompt, ProjectMcpTrust trust, Consumer<String> warn) {
        dropUntrustedProjectServers(merged, projectTree, others, projectDir, prompt, trust, warn);
        dropUntrustedProjectHooks(merged, projectTree, others, projectDir, prompt, trust, warn);
        refuseLiteralSecrets(projectTree);
        projectTokenStore(merged, projectTree, others, warn);
        dropUntrustedProjectOAuth(merged, projectTree, others, projectDir, prompt, trust, warn);
    }

    /**
     * Removes from {@code merged} the stdio MCP servers that only the project file defines (same definition not in
     * another layer) and that the user did not trust: asked once per project and definition, answer remembered.
     */
    static void dropUntrustedProjectServers(ObjectNode merged, JsonNode projectTree, List<JsonNode> others,
                                            Path projectDir, ProjectMcpTrust.Prompt prompt,
                                            ProjectMcpTrust trust, Consumer<String> warn) {
        if (!(merged.get("mcpServers") instanceof ArrayNode servers)) return;
        List<JsonNode> fromProject = new ArrayList<>();
        projectTree.path("mcpServers").forEach(fromProject::add);
        if (fromProject.isEmpty()) return;
        for (Iterator<JsonNode> it = servers.iterator(); it.hasNext(); ) {
            JsonNode s = it.next();
            String command = s.path("command").asText("");
            if (command.isBlank() || !fromProject.contains(s)) continue;
            boolean elsewhere = false;
            for (JsonNode t : others) {
                for (JsonNode o : t.path("mcpServers")) elsewhere |= o.equals(s);
            }
            if (elsewhere) continue;
            String name = s.path("name").asText("?");
            Boolean ok = trust.lookup(projectDir, name, s);
            if (ok == null) {
                StringBuilder line = new StringBuilder(command);
                s.path("args").forEach(a -> line.append(' ').append(a.asText()));
                ok = prompt.ask(projectDir, name, line.toString());
                if (ok != null) trust.remember(projectDir, name, s, ok);
            }
            if (!Boolean.TRUE.equals(ok)) {
                warn.accept("MCP server '" + name + "' from the project settings skipped: "
                        + (ok == null ? "it starts a local process and was never trusted (no prompt available here)"
                        : "not trusted"));
                it.remove();
            }
        }
    }

    /**
     * Removes from {@code merged} the stop command hooks that only the project file defines and that the user did not
     * trust: like a stdio MCP server, a hook runs a command chosen by whoever wrote the project. Asked once per
     * project and hook definition ({@link #STOP_HOOK_TRUST_PREFIX} + command), answer remembered.
     */
    static void dropUntrustedProjectHooks(ObjectNode merged, JsonNode projectTree, List<JsonNode> others,
                                          Path projectDir, ProjectMcpTrust.Prompt prompt,
                                          ProjectMcpTrust trust, Consumer<String> warn) {
        if (!(merged.path("hooks").get("stop") instanceof ArrayNode hooks)) return;
        List<JsonNode> fromProject = new ArrayList<>();
        projectTree.path("hooks").path("stop").forEach(fromProject::add);
        if (fromProject.isEmpty()) return;
        // a layer after the project file replaces the array: what is left from the project is what the project gave
        for (Iterator<JsonNode> it = hooks.iterator(); it.hasNext(); ) {
            JsonNode h = it.next();
            if (!fromProject.contains(h)) continue;
            boolean elsewhere = false;
            for (JsonNode t : others) {
                for (JsonNode o : t.path("hooks").path("stop")) elsewhere |= o.equals(h);
            }
            if (elsewhere) continue;
            String command = h.path("command").asText("");
            String name = STOP_HOOK_TRUST_PREFIX + command;
            Boolean ok = trust.lookup(projectDir, name, h);
            if (ok == null) {
                ok = prompt.ask(projectDir, name, command);
                if (ok != null) trust.remember(projectDir, name, h, ok);
            }
            if (!Boolean.TRUE.equals(ok)) {
                warn.accept("Stop hook '" + command + "' from the project settings skipped: "
                        + (ok == null ? "it runs a local command and was never trusted (no prompt available here)"
                        : "not trusted"));
                it.remove();
            }
        }
    }

    /** A project file may name a client secret only through {@code ${ENV}}: the file is shared, the secret is not. */
    static void refuseLiteralSecrets(JsonNode projectTree) {
        for (JsonNode api : projectTree.path("restApis")) {
            JsonNode secret = api.path("oauth").get("clientSecret");
            if (secret != null && !secret.isNull() && !ENV_ONLY.matcher(secret.asText()).matches()) {
                throw new IllegalArgumentException("REST API '" + api.path("name").asText("?")
                        + "': oauth.clientSecret in the project settings must be an ${ENV_VAR} reference, not a literal");
            }
        }
    }

    /**
     * The {@code tokenStore} section counts from the user settings and {@code --settings} only: a project cannot
     * choose where (or how unprotected) a user's tokens are kept.
     */
    static void projectTokenStore(ObjectNode merged, JsonNode projectTree, List<JsonNode> others,
                                  Consumer<String> warn) {
        if (!projectTree.has("tokenStore")) return;
        warn.accept("tokenStore in the project settings ignored: it is read from the user settings only");
        JsonNode kept = null;
        for (JsonNode t : others) {
            JsonNode ts = t.get("tokenStore");
            if (ts != null) kept = kept == null ? ts.deepCopy() : HarnessSettingsLoader.merge(kept, ts.deepCopy());
        }
        if (kept == null) merged.remove("tokenStore");
        else merged.set("tokenStore", kept);
    }

    /**
     * Removes the REST APIs with {@code oauth} that only the project file defines and that the user did not trust:
     * the block decides where a user's token is sent. Asked once per project and definition
     * ({@link #OAUTH_TRUST_PREFIX} + name), answer remembered.
     */
    static void dropUntrustedProjectOAuth(ObjectNode merged, JsonNode projectTree, List<JsonNode> others,
                                          Path projectDir, ProjectMcpTrust.Prompt prompt,
                                          ProjectMcpTrust trust, Consumer<String> warn) {
        if (!(merged.get("restApis") instanceof ArrayNode apis)) return;
        List<JsonNode> fromProject = new ArrayList<>();
        projectTree.path("restApis").forEach(fromProject::add);
        if (fromProject.isEmpty()) return;
        for (Iterator<JsonNode> it = apis.iterator(); it.hasNext(); ) {
            JsonNode a = it.next();
            if (!a.has("oauth") || a.get("oauth").isNull() || !fromProject.contains(a)) continue;
            boolean elsewhere = false;
            for (JsonNode t : others) {
                for (JsonNode o : t.path("restApis")) elsewhere |= o.equals(a);
            }
            if (elsewhere) continue;
            String api = a.path("name").asText("?");
            String name = OAUTH_TRUST_PREFIX + api;
            Boolean ok = trust.lookup(projectDir, name, a);
            if (ok == null) {
                JsonNode o = a.get("oauth");
                StringBuilder where = new StringBuilder("OAuth login for REST API '" + api + "' sending your token to ");
                where.append(a.path("baseUrl").asText(a.path("openApiUrl").asText(a.path("openApiFile").asText("?"))));
                for (String k : List.of("issuer", "authorizationUrl", "tokenUrl")) {
                    if (o.hasNonNull(k)) where.append(", ").append(k).append(' ').append(o.get(k).asText());
                }
                ok = prompt.ask(projectDir, name, where.toString());
                if (ok != null) trust.remember(projectDir, name, a, ok);
            }
            if (!Boolean.TRUE.equals(ok)) {
                warn.accept("REST API '" + api + "' from the project settings skipped: its oauth block "
                        + (ok == null ? "was never trusted (no prompt available here)" : "is not trusted"));
                it.remove();
            }
        }
    }
}

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

    /** Name prefix under which a project REST API with oauth is asked about and remembered in {@link FolderTrust}. */
    public static final String OAUTH_TRUST_PREFIX = "oauth:api:";
    /** Name under which a project stop hook is asked about and remembered in {@link FolderTrust}. */
    public static final String STOP_HOOK_TRUST_PREFIX = "stop-hook:";

    /** Name prefix under which a loosening setting is asked about ({@code loosen:<key>}), see {@link #LOOSENING_KEYS}. */
    public static final String LOOSEN_TRUST_PREFIX = "loosen:";
    /** The pseudo key of a project {@code .jllm/SYSTEM.md}, which replaces the built-in system prompt. */
    public static final String SYSTEM_MD_KEY = "SYSTEM.md";
    /**
     * The project settings that can loosen what the user set globally; they apply only once approved for the folder
     * ({@link FolderTrust#approveLoosening}), also when the folder is trusted. A trailing {@code .*} covers every
     * setting below the prefix.
     */
    public static final List<String> LOOSENING_KEYS = List.of("permissions.allow", "permissions.ask", "sandbox.*",
            "output.taintGating", "output.taintDocuments", "output.sanitize", "web.*", "whitelistedEnvVars", "toolsWhitelistedEnvVars",
            SYSTEM_MD_KEY);

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
                      FolderTrust.Prompt prompt, FolderTrust trust, Consumer<String> warn) {
        dropUntrustedProjectServers(merged, projectTree, others, projectDir, prompt, trust, warn);
        dropUntrustedProjectHooks(merged, projectTree, others, projectDir, prompt, trust, warn);
        refuseLiteralSecrets(projectTree);
        projectTokenStore(merged, projectTree, others, warn);
        dropUntrustedProjectOAuth(merged, projectTree, others, projectDir, prompt, trust, warn);
        projectTrustedProjects(merged, projectTree, others, warn);
        dropUnapprovedLoosenings(merged, projectTree, others, projectDir, prompt, trust, warn);
    }

    /** One project setting that loosens the other layers: what to show, and how to put the other layers' value back. */
    private record Loosening(String key, String diff, Runnable revert) {
    }

    /** {@code trustedProjects} (the folders that run unattended) comes from the user's layers only. */
    static void projectTrustedProjects(ObjectNode merged, JsonNode projectTree, List<JsonNode> others,
                                       Consumer<String> warn) {
        if (!projectTree.has("trustedProjects")) return;
        warn.accept("trustedProjects in the project settings ignored: it is read from the user settings only");
        JsonNode kept = null;
        for (JsonNode t : others) if (t.has("trustedProjects")) kept = t.get("trustedProjects");
        if (kept == null) merged.remove("trustedProjects");
        else merged.set("trustedProjects", kept.deepCopy());
    }

    /**
     * Puts back the value of the other layers for every project setting of {@link #LOOSENING_KEYS} that loosens it
     * (tightening applies silently) and that the user did not approve for this folder: asked once (name
     * {@link #LOOSEN_TRUST_PREFIX} + key, the question is the diff), approval remembered in the folder's record. With
     * no one to ask the loosening is dropped.
     */
    static void dropUnapprovedLoosenings(ObjectNode merged, JsonNode projectTree, List<JsonNode> others,
                                         Path projectDir, FolderTrust.Prompt prompt, FolderTrust trust,
                                         Consumer<String> warn) {
        JsonNode baseline = null;
        for (JsonNode t : others) {
            baseline = baseline == null ? t.deepCopy() : HarnessSettingsLoader.merge(baseline, t.deepCopy());
        }
        if (baseline == null) baseline = merged.objectNode();
        java.util.Set<String> approved = trust.record(projectDir)
                .filter(r -> r.sha256().equals(FolderTrust.hashOf(projectDir)))
                .map(FolderTrust.Record::loosenings).orElse(java.util.Set.of());
        for (Loosening l : findLoosenings(merged, projectTree, baseline)) {
            if (approved.contains(l.key())) continue;
            Boolean ok = prompt.ask(projectDir, LOOSEN_TRUST_PREFIX + l.key(), l.diff());
            if (Boolean.TRUE.equals(ok)) {
                trust.approveLoosening(projectDir, l.key());
                continue;
            }
            l.revert().run();
            warn.accept("Project setting '" + l.key() + "' ignored: it loosens your settings (" + l.diff() + ") and "
                    + (ok == null ? "was never approved for this folder (no prompt available here)"
                    : "was not approved"));
        }
    }

    private static List<Loosening> findLoosenings(ObjectNode merged, JsonNode project, JsonNode baseline) {
        List<Loosening> out = new ArrayList<>();
        // permission rules: an added allow, a removed ask
        List<String> pAllow = strings(at(project, "permissions.allow"));
        List<String> bAllow = strings(at(baseline, "permissions.allow"));
        if (pAllow.stream().anyMatch(x -> !bAllow.contains(x)) && effective(merged, project, "permissions.allow")) {
            out.add(revertable(merged, baseline, "permissions.allow"));
        }
        List<String> pAsk = strings(at(project, "permissions.ask"));
        List<String> bAsk = strings(at(baseline, "permissions.ask"));
        if (project.path("permissions").has("ask") && bAsk.stream().anyMatch(x -> !pAsk.contains(x))
                && effective(merged, project, "permissions.ask")) {
            out.add(revertable(merged, baseline, "permissions.ask"));
        }
        // sandbox.*, web.*: any change, but the known stricter ones
        for (String section : List.of("sandbox", "web")) {
            for (var leaf : leaves(project.path(section), section)) {
                String path = leaf.getKey();
                JsonNode value = leaf.getValue();
                if (value.equals(at(baseline, path)) || !effective(merged, project, path) || tighter(path, value)) {
                    continue;
                }
                out.add(revertable(merged, baseline, path));
            }
        }
        // output
        for (String key : List.of("output.taintGating", "output.taintDocuments")) {
            JsonNode taint = at(project, key);
            JsonNode baseTaint = at(baseline, key);
            boolean baseOn = key.equals("output.taintGating") ? !(baseTaint != null && !baseTaint.asBoolean(true))
                    : baseTaint != null && baseTaint.asBoolean(false);
            if (taint != null && taint.isBoolean() && !taint.asBoolean() && baseOn && effective(merged, project, key)) {
                out.add(revertable(merged, baseline, key));
            }
        }
        JsonNode san = at(project, "output.sanitize");
        if (san != null && san.isTextual() && effective(merged, project, "output.sanitize")) {
            JsonNode b = at(baseline, "output.sanitize");
            if (sanitizeRank(san.asText()) < sanitizeRank(b == null ? null : b.asText())) {
                out.add(revertable(merged, baseline, "output.sanitize"));
            }
        }
        // environment: a name the other layers do not list (they list "*" when every non-secret name passes already)
        for (String key : List.of("whitelistedEnvVars", "toolsWhitelistedEnvVars")) {
            List<String> p = strings(at(project, key));
            JsonNode bn = at(baseline, key);
            List<String> b = bn == null ? org.kendar.llm.harness.util.EnvWhitelist.defaults() : strings(bn);
            if (!b.contains("*") && p.stream().anyMatch(x -> !b.contains(x)) && effective(merged, project, key)) {
                out.add(revertable(merged, baseline, key));
            }
        }
        return out;
    }

    private static Loosening revertable(ObjectNode merged, JsonNode baseline, String path) {
        JsonNode base = at(baseline, path);
        JsonNode now = at(merged, path);
        String diff = path + ": " + (base == null ? "(unset)" : base) + " -> " + now;
        return new Loosening(path, diff, () -> put(merged, path, base == null ? null : base.deepCopy()));
    }

    /** False when a layer after the project replaced the value: that one is the user's own and stays. */
    private static boolean effective(ObjectNode merged, JsonNode project, String path) {
        JsonNode m = at(merged, path);
        return m != null && m.equals(at(project, path));
    }

    /** The values that make a setting stricter than whatever the other layers say. */
    private static boolean tighter(String path, JsonNode value) {
        return switch (path) {
            case "sandbox.backend" -> "docker".equalsIgnoreCase(value.asText());
            case "sandbox.docker.profile" -> "hardened".equalsIgnoreCase(value.asText());
            case "sandbox.localFallback" -> value.isBoolean() && !value.asBoolean();
            case "web.blockLocalHost" -> value.isBoolean() && value.asBoolean();
            default -> false;
        };
    }

    private static int sanitizeRank(String mode) {
        if (mode == null || mode.isBlank()) return 1;
        return switch (mode.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "off", "false", "none" -> 0;
            case "strict" -> 2;
            default -> 1;
        };
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) n.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static JsonNode at(JsonNode root, String dotted) {
        JsonNode n = root;
        for (String k : dotted.split("\\.")) {
            if (n == null || !n.has(k)) return null;
            n = n.get(k);
        }
        return n == null || n.isNull() ? null : n;
    }

    private static void put(ObjectNode root, String dotted, JsonNode value) {
        String[] k = dotted.split("\\.");
        ObjectNode n = root;
        for (int i = 0; i < k.length - 1; i++) {
            JsonNode c = n.get(k[i]);
            if (c instanceof ObjectNode o) {
                n = o;
            } else {
                if (value == null) return;
                n = n.putObject(k[i]);
            }
        }
        if (value == null) n.remove(k[k.length - 1]);
        else n.set(k[k.length - 1], value);
    }

    private static List<java.util.Map.Entry<String, JsonNode>> leaves(JsonNode n, String prefix) {
        List<java.util.Map.Entry<String, JsonNode>> out = new ArrayList<>();
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> out.addAll(leaves(e.getValue(), prefix + "." + e.getKey())));
        } else if (!n.isMissingNode() && !n.isNull()) {
            out.add(java.util.Map.entry(prefix, n));
        }
        return out;
    }

    /**
     * Removes from {@code merged} the stdio MCP servers that only the project file defines (same definition not in
     * another layer) and that the user did not trust: asked once per project and definition, answer remembered.
     */
    static void dropUntrustedProjectServers(ObjectNode merged, JsonNode projectTree, List<JsonNode> others,
                                            Path projectDir, FolderTrust.Prompt prompt,
                                            FolderTrust trust, Consumer<String> warn) {
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
                                          Path projectDir, FolderTrust.Prompt prompt,
                                          FolderTrust trust, Consumer<String> warn) {
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
                                          Path projectDir, FolderTrust.Prompt prompt,
                                          FolderTrust trust, Consumer<String> warn) {
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

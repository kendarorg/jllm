package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.auth.AuthProvider;
import org.kendar.llm.harness.auth.McpOAuthLogin;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.tools.TruncationMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The MCP servers and REST APIs of a harness. Enabling a source discovers its tools and registers them; disabling
 * unregisters them. State is in memory: the {@code enabled} flag of the settings is only the startup default.
 * <p>
 * Two tools with the same name (after sanitizing) are a configuration error: at construction it is thrown as a
 * {@link ToolNames.CollisionException} after every opened client was closed; at runtime {@link #enable} refuses the
 * source. An MCP server lost for good (see {@link McpToolProvider#connect}) disables its source and warns the run that
 * hit the failure. {@link #close()} stops every MCP client (and so every stdio server process).
 */
public final class ExternalSources implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExternalSources.class);

    /** The context of the external tool call running on this thread: where a warning of that call goes. */
    private static final ThreadLocal<ToolContext> CALLER = new ThreadLocal<>();

    public enum Kind {
        MCP("mcp"), API("api");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }
    }

    /** @param error last discovery or check failure, null when none */
    public record Status(Kind kind, String name, boolean enabled, int tools, String error) {
    }

    public record CheckResult(boolean ok, long millis, int tools, String message) {
    }

    private static final class Source {
        final Kind kind;
        final String name;
        final Supplier<List<HarnessTool>> discover;
        final Supplier<String> probe;
        final boolean startEnabled;
        boolean enabled;
        int tools;
        /** Names of the tools now in the registry for this source. */
        Set<String> registered = Set.of();
        String error;

        Source(Kind kind, String name, Supplier<List<HarnessTool>> discover, Supplier<String> probe,
               boolean startEnabled) {
            this.kind = kind;
            this.name = name;
            this.discover = discover;
            this.probe = probe;
            this.startEnabled = startEnabled;
        }
    }

    private final HarnessToolRegistry registry;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, McpClient> connections = new HashMap<>();
    private final ToolNames.Claims claims = new ToolNames.Claims();
    private final java.util.List<String> toolEnv;
    private final org.kendar.llm.harness.auth.Identity identity;
    private TokenStore tokenStore;
    private final java.util.function.Function<RestApiSettings, Map<String, String>> specHeaders;
    private boolean closed;

    public ExternalSources(HarnessToolRegistry registry, HarnessSettings settings, HttpClient http,
                           ObjectMapper mapper) {
        this(registry, settings, http, mapper, org.kendar.llm.harness.auth.Identity.ANONYMOUS);
    }

    /** @param identity who each MCP and REST call is made for; the single-user default is {@code Identity.ANONYMOUS} */
    public ExternalSources(HarnessToolRegistry registry, HarnessSettings settings, HttpClient http,
                           ObjectMapper mapper, org.kendar.llm.harness.auth.Identity identity) {
        this(registry, settings, http, mapper, identity, null, null);
    }

    /**
     * @param tokenStore where the OAuth tokens of MCP servers are kept, shared with the rest of the harness; null: one
     *                   {@link TokenStore#defaultStore()}, created on first use and shared by every server
     * @param specHeaders extra headers to fetch the spec of a REST API with (a service token for
     *                    {@code oauth.specAuth: client_credentials}); called at each discovery; null: none
     * @throws ToolNames.CollisionException when two tools of the sources enabled at start get the same name, or one
     *                                      takes the name of a harness tool; every client opened is closed first
     */
    public ExternalSources(HarnessToolRegistry registry, HarnessSettings settings, HttpClient http,
                           ObjectMapper mapper, org.kendar.llm.harness.auth.Identity identity,
                           TokenStore tokenStore,
                           java.util.function.Function<RestApiSettings, Map<String, String>> specHeaders) {
        this.specHeaders = specHeaders == null ? a -> Map.of() : specHeaders;
        this.registry = registry;
        this.toolEnv = settings.toolsWhitelistedEnvVars();
        this.http = http;
        this.mapper = mapper;
        this.identity = identity;
        this.tokenStore = tokenStore;
        try {
            for (McpServerSettings m : settings.mcpServers()) {
                add(new Source(Kind.MCP, m.name(), () -> mcpDiscover(m), () -> mcpProbe(m), m.active()));
            }
            for (RestApiSettings a : settings.restApis()) {
                add(new Source(Kind.API, a.name(), () -> RestApiToolProvider.discover(a, http, mapper, identity, claims,
                        this.specHeaders.apply(a)),
                        () -> apiProbe(a), a.active()));
            }
            synchronized (this) {
                for (Source s : sources.values()) {
                    if (s.startEnabled) enable(s, true);
                }
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void add(Source s) {
        if (sources.putIfAbsent(key(s.kind, s.name), s) != null) {
            throw new IllegalArgumentException("Duplicate " + s.kind.prefix + " source '" + s.name + "'");
        }
    }

    public synchronized List<Status> list(Kind kind) {
        List<Status> out = new ArrayList<>();
        for (Source s : sources.values()) {
            if (s.kind == kind) out.add(new Status(s.kind, s.name, s.enabled, s.tools, s.error));
        }
        return out;
    }

    /**
     * A failure, a name collision included, leaves the source disabled.
     *
     * @return null on success, else why the source stays disabled
     */
    public synchronized String enable(Kind kind, String name) {
        Source s = require(kind, name);
        if (closed) return "external sources are closed";
        return enable(s, false);
    }

    /** @param startup a name collision is thrown instead of returned */
    private String enable(Source s, boolean startup) {
        String key = key(s.kind, s.name);
        try {
            List<HarnessTool> tools = s.discover.get();
            checkHarnessNames(s, tools);
            registry.setSource(key, tools);
            s.enabled = true;
            s.tools = tools.size();
            Set<String> names = new HashSet<>();
            tools.forEach(t -> names.add(t.name()));
            s.registered = names;
            s.error = null;
            return null;
        } catch (RuntimeException e) {
            drop(s);
            s.error = String.valueOf(e.getMessage());
            if (e instanceof ToolNames.CollisionException) {
                if (startup) throw e;
                log.warn("{} source '{}' not enabled: {}", s.kind.prefix, s.name, s.error);
            }
            return s.error;
        }
    }

    /** A tool of the source must not take the name of a tool of the harness (standard, injected or plugin). */
    private void checkHarnessNames(Source s, List<HarnessTool> tools) {
        for (HarnessTool t : tools) {
            if (registry.get(t.name()) == null || s.registered.contains(t.name())) continue;
            String mine = claims.owner(t.name());
            throw new ToolNames.CollisionException(t.name(), "harness tool '" + t.name() + "'",
                    mine != null ? mine : s.kind.prefix + " source '" + s.name + "' tool '" + t.name() + "'");
        }
    }

    public synchronized void disable(Kind kind, String name) {
        drop(require(kind, name));
    }

    /** Unregisters the tools of the source, frees their names and closes its connection. Lock held. */
    private void drop(Source s) {
        String key = key(s.kind, s.name);
        registry.removeSource(key);
        claims.release(key);
        closeConnection(key);
        s.enabled = false;
        s.tools = 0;
        s.registered = Set.of();
    }

    /** Whether the source answers. Does not change what is registered. */
    public CheckResult check(Kind kind, String name) {
        Source s;
        synchronized (this) {
            s = require(kind, name);
        }
        long start = System.nanoTime();
        try {
            String message = s.probe.get();
            long ms = (System.nanoTime() - start) / 1_000_000;
            synchronized (this) {
                s.error = null;
            }
            return new CheckResult(true, ms, s.tools, message);
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            synchronized (this) {
                s.error = String.valueOf(e.getMessage());
            }
            return new CheckResult(false, ms, s.tools, String.valueOf(e.getMessage()));
        }
    }

    /** Closes every MCP client, stopping the stdio server processes. Safe to call twice; enable is refused after. */
    @Override
    public void close() {
        List<McpClient> open;
        synchronized (this) {
            if (closed) return;
            closed = true;
            open = new ArrayList<>(connections.values());
            connections.clear();
        }
        open.forEach(ExternalSources::closeQuietly);
    }

    private Source require(Kind kind, String name) {
        Source s = sources.get(key(kind, name));
        if (s == null) throw new IllegalArgumentException("Unknown " + kind.prefix + " '" + name + "'");
        return s;
    }

    private static String key(Kind kind, String name) {
        return kind.prefix + ":" + name;
    }

    /**
     * Ends the MCP session of a principal on a server (logout): its next call opens a new one with its new token.
     * Does nothing for an unknown or disabled server.
     */
    public void closeSession(String server, String principalId) {
        McpClient c;
        synchronized (this) {
            c = connections.get(key(Kind.MCP, server));
        }
        if (c != null) c.closeSession(principalId);
    }

    /** Ends the sessions of a principal on every MCP server (user deleted). */
    public void closeSessions(String principalId) {
        List<McpClient> open;
        synchronized (this) {
            open = new ArrayList<>(connections.values());
        }
        open.forEach(c -> c.closeSession(principalId));
    }

    private synchronized TokenStore tokenStore() {
        if (tokenStore == null) tokenStore = TokenStore.defaultStore();
        return tokenStore;
    }

    private AuthProvider mcpOAuth(McpServerSettings m) {
        return new McpOAuthLogin(m.name(), m.url(), http, mapper).authProvider(tokenStore(), null);
    }

    private List<HarnessTool> mcpDiscover(McpServerSettings m) {
        String key = key(Kind.MCP, m.name());
        AtomicReference<McpClient> self = new AtomicReference<>();
        McpClient client = McpToolProvider.connect(m, http, mapper, toolEnv, this::mcpOAuth,
                reason -> onMcpUnavailable(m.name(), self.get(), reason));
        self.set(client);
        try {
            List<HarnessTool> tools = McpToolProvider.tools(m, client, mapper, identity, claims);
            closeConnection(key);
            connections.put(key, client);
            return wrap(tools);
        } catch (java.io.IOException e) {
            closeQuietly(client);
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            closeQuietly(client);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (RuntimeException e) {
            closeQuietly(client);
            throw e;
        }
    }

    /**
     * The client of an enabled MCP server gave up (session lost, process not restartable): the source is disabled and
     * the run whose call failed is warned. Runs on the thread of that call, outside the client's locks.
     */
    private void onMcpUnavailable(String name, McpClient client, String reason) {
        String key = key(Kind.MCP, name);
        synchronized (this) {
            Source s = sources.get(key);
            if (s == null || closed || client == null || connections.get(key) != client) return; // stale client
            drop(s);
            s.error = reason;
        }
        String message = "MCP server '" + name + "' disabled: " + reason + " (re-enable it with /mcp)";
        log.warn(message);
        ToolContext caller = CALLER.get();
        if (caller == null || caller.run() == null || caller.run().sink() == null) return;
        try {
            caller.run().sink().emit("warning", mapper.createObjectNode().put("message", message));
        } catch (RuntimeException e) {
            log.debug("Cannot emit MCP warning: {}", e.toString());
        }
    }

    private void closeConnection(String key) {
        McpClient old = connections.remove(key);
        if (old != null) closeQuietly(old);
    }

    private static void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (java.io.IOException e) {
            log.debug("MCP connection did not close cleanly: {}", e.toString());
        }
    }

    private String mcpProbe(McpServerSettings m) {
        try (McpClient client = McpToolProvider.connect(m, http, mapper, toolEnv, this::mcpOAuth)) {
            int n = client.listTools().size();
            return n + " tools offered";
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** The spec must parse and the base URL must answer; any HTTP status counts as reachable. */
    private String apiProbe(RestApiSettings a) {
        String base = RestApiToolProvider.resolveBaseUrl(a);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(10)).GET();
            a.headers().forEach(b::header);
            HttpResponse<Void> r = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
            return "spec ok, " + base + " answered HTTP " + r.statusCode();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(base + " unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static List<HarnessTool> wrap(List<HarnessTool> tools) {
        List<HarnessTool> out = new ArrayList<>(tools.size());
        for (HarnessTool t : tools) out.add(new CallerTracking(t));
        return out;
    }

    /** Makes the context of a running MCP call visible to {@link #onMcpUnavailable}, which runs on its thread. */
    private record CallerTracking(HarnessTool tool) implements HarnessTool {
        @Override
        public String name() {
            return tool.name();
        }

        @Override
        public String description() {
            return tool.description();
        }

        @Override
        public JsonNode inputSchema() {
            return tool.inputSchema();
        }

        @Override
        public String group() {
            return tool.group();
        }

        @Override
        public ToolKind kind() {
            return tool.kind();
        }

        @Override
        public TruncationMode truncation() {
            return tool.truncation();
        }

        @Override
        public String execute(ToolContext ctx, JsonNode input) throws Exception {
            ToolContext previous = CALLER.get();
            CALLER.set(ctx);
            try {
                return tool.execute(ctx, input);
            } finally {
                if (previous == null) CALLER.remove();
                else CALLER.set(previous);
            }
        }

        @Override
        public String preview(ToolContext ctx, JsonNode input) {
            return tool.preview(ctx, input);
        }
    }
}

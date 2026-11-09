package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * The MCP servers and REST APIs of a harness. Enabling a source discovers its tools and registers them; disabling
 * unregisters them. State is in memory: the {@code enabled} flag of the settings is only the startup default.
 */
public final class ExternalSources {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExternalSources.class);

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
        boolean enabled;
        int tools;
        String error;

        Source(Kind kind, String name, Supplier<List<HarnessTool>> discover, Supplier<String> probe) {
            this.kind = kind;
            this.name = name;
            this.discover = discover;
            this.probe = probe;
        }
    }

    private final HarnessToolRegistry registry;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, McpClient> connections = new HashMap<>();

    public ExternalSources(HarnessToolRegistry registry, HarnessSettings settings, HttpClient http,
                           ObjectMapper mapper) {
        this.registry = registry;
        this.http = http;
        this.mapper = mapper;
        for (McpServerSettings m : settings.mcpServers()) {
            add(new Source(Kind.MCP, m.name(), () -> mcpDiscover(m), () -> mcpProbe(m)), m.active());
        }
        for (RestApiSettings a : settings.restApis()) {
            add(new Source(Kind.API, a.name(), () -> RestApiToolProvider.discover(a, http, mapper),
                    () -> apiProbe(a)), a.active());
        }
    }

    private void add(Source s, boolean startEnabled) {
        if (sources.putIfAbsent(key(s.kind, s.name), s) != null) {
            throw new IllegalArgumentException("Duplicate " + s.kind.prefix + " source '" + s.name + "'");
        }
        if (startEnabled) enable(s.kind, s.name);
    }

    public synchronized List<Status> list(Kind kind) {
        List<Status> out = new ArrayList<>();
        for (Source s : sources.values()) {
            if (s.kind == kind) out.add(new Status(s.kind, s.name, s.enabled, s.tools, s.error));
        }
        return out;
    }

    /** @return null on success, else why the source stays disabled */
    public synchronized String enable(Kind kind, String name) {
        Source s = require(kind, name);
        try {
            List<HarnessTool> tools = s.discover.get();
            registry.setSource(key(kind, name), tools);
            s.enabled = true;
            s.tools = tools.size();
            s.error = null;
            return null;
        } catch (RuntimeException e) {
            s.enabled = false;
            s.error = String.valueOf(e.getMessage());
            return s.error;
        }
    }

    public synchronized void disable(Kind kind, String name) {
        Source s = require(kind, name);
        registry.removeSource(key(kind, name));
        closeConnection(key(kind, name));
        s.enabled = false;
        s.tools = 0;
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

    private Source require(Kind kind, String name) {
        Source s = sources.get(key(kind, name));
        if (s == null) throw new IllegalArgumentException("Unknown " + kind.prefix + " '" + name + "'");
        return s;
    }

    private static String key(Kind kind, String name) {
        return kind.prefix + ":" + name;
    }

    private List<HarnessTool> mcpDiscover(McpServerSettings m) {
        String key = key(Kind.MCP, m.name());
        McpClient client = McpToolProvider.connect(m, http, mapper);
        try {
            List<HarnessTool> tools = McpToolProvider.tools(m, client, mapper);
            closeConnection(key);
            connections.put(key, client);
            return tools;
        } catch (java.io.IOException e) {
            closeQuietly(client);
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            closeQuietly(client);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
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
        try (McpClient client = McpToolProvider.connect(m, http, mapper)) {
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
}

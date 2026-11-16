package org.kendar.llm.web.commons.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginEvents;
import org.kendar.llm.harness.plugin.PluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.plugin.WebContext;
import org.kendar.llm.harness.plugin.WebHandler;
import org.kendar.llm.harness.plugin.WebPlugin;
import org.kendar.llm.harness.plugin.WebRequest;
import org.kendar.llm.harness.plugin.WebResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Loads web plugins and mounts their neutral handlers. Knows no web framework: a controller turns an HTTP call into a
 * {@link WebRequest} and calls {@link #dispatch}. A plugin can only ever be reached under its own id.
 */
public final class WebPluginHost {
    private record Route(String method, String[] segments, WebHandler handler) {
    }

    private final ServiceRegistry services;
    private final PluginStorages storages;
    private final JsonNode pluginsSettings;
    private final Map<String, WebPlugin> loaded = new LinkedHashMap<>();
    private final Map<String, List<Route>> routes = new ConcurrentHashMap<>();
    private final Map<String, Hub> hubs = new ConcurrentHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /** @param pluginsSettings the {@code plugins} object: {@code <id>: {enabled, settings}}; may be null */
    public WebPluginHost(ServiceRegistry services, PluginStorages storages, JsonNode pluginsSettings) {
        this.services = services;
        this.storages = storages;
        this.pluginsSettings = pluginsSettings == null ? JsonNodeFactory.instance.objectNode() : pluginsSettings;
    }

    /** Web plugins on the classpath and in the jars of {@code dir} (may be null). */
    public static List<WebPlugin> discover(Path dir) {
        List<WebPlugin> out = new ArrayList<>();
        for (WebPlugin p : ServiceLoader.load(WebPlugin.class)) out.add(p);
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path jar : s.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                        WebPluginHost.class.getClassLoader()); // kept open for the life of the process
                for (var provider : ServiceLoader.load(WebPlugin.class, loader).stream().toList()) {
                    if (provider.type().getClassLoader() == loader) out.add(provider.get());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public List<String> loaded() {
        return List.copyOf(loaded.keySet());
    }

    /**
     * Installs the plugins that are listed and enabled and whose required plugins are loaded.
     * Anything else is skipped with a warning.
     */
    public synchronized void load(List<WebPlugin> candidates) {
        Map<String, WebPlugin> byId = new LinkedHashMap<>();
        for (WebPlugin p : candidates) {
            String id = p.id();
            if (id == null || !HarnessPlugin.ID_PATTERN.matcher(id).matches()) {
                throw new IllegalStateException("Web plugin " + p.getClass().getName() + " has the invalid id " + id);
            }
            if (byId.put(id, p) != null) throw new IllegalStateException("Two web plugins have the id " + id);
        }
        List<String> pending = new ArrayList<>();
        for (var e : byId.entrySet()) {
            JsonNode entry = pluginsSettings.get(e.getKey());
            if (entry == null) {
                warnings.add("web plugin " + e.getKey() + " found but not listed under plugins; not loaded");
            } else if (!entry.path("enabled").asBoolean(false)) {
                warnings.add("web plugin " + e.getKey() + " is disabled");
            } else pending.add(e.getKey());
        }
        boolean progress = true;
        while (progress && !pending.isEmpty()) { // dependencies first
            progress = false;
            for (String id : List.copyOf(pending)) {
                WebPlugin p = byId.get(id);
                if (loaded.keySet().containsAll(p.requires())) {
                    try {
                        install(p);
                    } catch (RuntimeException e) { // a plugin that cannot start is skipped, the application still runs
                        routes.remove(id);
                        hubs.remove(id);
                        warnings.add("web plugin " + id + " skipped: " + e.getMessage());
                    }
                    pending.remove(id);
                    progress = true;
                }
            }
        }
        for (String id : pending) {
            warnings.add("web plugin " + id + " skipped: it needs " + byId.get(id).requires()
                    + " which is missing or disabled");
        }
    }

    private void install(WebPlugin p) {
        String id = p.id();
        List<Route> mine = new CopyOnWriteArrayList<>();
        Hub hub = new Hub();
        JsonNode settings = pluginsSettings.path(id).path("settings");
        WebContext ctx = new WebContext() {
            @Override
            public JsonNode settings() {
                return settings.isMissingNode() ? JsonNodeFactory.instance.objectNode() : settings;
            }

            @Override
            public PluginStorage storage() {
                return storages.forPlugin(id);
            }

            @Override
            public PluginEvents events() {
                return hub;
            }

            @Override
            public ServiceRegistry services() {
                return services;
            }

            @Override
            public WebContext route(String method, String pattern, WebHandler handler) {
                mine.add(new Route(method.toUpperCase(), split(pattern), handler));
                return this;
            }
        };
        p.install(ctx);
        routes.put(id, mine);
        hubs.put(id, hub);
        loaded.put(id, p);
    }

    /** Unloads every plugin; their routes disappear. */
    public synchronized void close() {
        for (WebPlugin p : loaded.values()) {
            try {
                p.close();
            } catch (RuntimeException e) {
                warnings.add("web plugin " + p.id() + " failed to close: " + e.getMessage());
            }
        }
        loaded.clear();
        routes.clear();
        hubs.clear();
    }

    public boolean has(String pluginId) {
        return routes.containsKey(pluginId);
    }

    /** The plugin's event channel; null when the plugin is not loaded. */
    public PluginEvents events(String pluginId) {
        return hubs.get(pluginId);
    }

    public WebResponse dispatch(String pluginId, WebRequest req) {
        List<Route> rs = routes.get(pluginId);
        if (rs == null) return WebResponse.error(404, "No such plugin");
        String[] path = split(req.path());
        boolean pathKnown = false;
        for (Route r : rs) {
            Map<String, String> params = match(r.segments(), path);
            if (params == null) continue;
            pathKnown = true;
            if (!r.method().equals(req.method().toUpperCase())) continue;
            try {
                return r.handler().handle(req.withPathParams(params));
            } catch (Exception e) {
                return WebResponse.error(500, String.valueOf(e.getMessage()));
            }
        }
        return pathKnown ? WebResponse.error(405, "Method not allowed") : WebResponse.error(404, "Not found");
    }

    private static String[] split(String p) {
        return Stream.of(p.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static Map<String, String> match(String[] pattern, String[] path) {
        if (pattern.length != path.length) return null;
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].startsWith("{") && pattern[i].endsWith("}")) {
                m.put(pattern[i].substring(1, pattern[i].length() - 1), path[i]);
            } else if (!pattern[i].equals(path[i])) return null;
        }
        return m;
    }

    private static final class Hub implements PluginEvents {
        private final Map<String, List<Consumer<Event>>> listeners = new ConcurrentHashMap<>();

        @Override
        public void push(String user, String type, JsonNode data) {
            for (var l : listeners.getOrDefault(user, List.of())) {
                try {
                    l.accept(new Event(type, data));
                } catch (RuntimeException ignored) {
                    // a dead listener must not stop the others
                }
            }
        }

        @Override
        public Runnable subscribe(String user, Consumer<Event> listener) {
            listeners.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(listener);
            return () -> listeners.getOrDefault(user, new CopyOnWriteArrayList<>()).remove(listener);
        }
    }
}

package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Decides which plugins get installed: applies ids, the allow-list under {@code plugins} and the enabled flags.
 * Pure: it returns what to install and the messages to log.
 */
public final class PluginLoader {

    private PluginLoader() {
    }

    /** A plugin and where it came from ({@code classpath}, a jar path, or {@code code} for an explicit one). */
    public record Candidate(HarnessPlugin plugin, String source) {
    }

    public record Install(HarnessPlugin plugin, JsonNode settings) {
    }

    /** @param summary the one-line startup summary */
    public record Result(List<Install> install, List<String> warnings, List<String> infos, List<String> loaded,
                         List<String> disabled, List<String> notListed) {
        public String summary() {
            return "plugins loaded: " + loaded + ", disabled: " + disabled + ", not listed: " + notListed;
        }
    }

    /** The plugins of the classpath and of {@code pluginDir} (may be null), through {@link ServiceLoader}. */
    public static List<Candidate> discover(Path pluginDir, boolean dirMustExist, List<String> warnings) {
        List<Candidate> out = new ArrayList<>();
        for (HarnessPlugin p : ServiceLoader.load(HarnessPlugin.class)) out.add(new Candidate(p, "classpath"));
        if (pluginDir == null) return out;
        if (!Files.isDirectory(pluginDir)) {
            if (dirMustExist) warnings.add("plugin directory " + pluginDir + " does not exist");
            return out;
        }
        List<Path> jars;
        try (Stream<Path> s = Files.list(pluginDir)) {
            jars = s.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list plugin directory " + pluginDir, e);
        }
        for (Path jar : jars) {
            try {
                // one loader per jar: two versions of a plugin are both seen (and fail as a duplicate id)
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                        PluginLoader.class.getClassLoader()); // kept open for the life of the process
                for (var provider : ServiceLoader.load(HarnessPlugin.class, loader).stream().toList()) {
                    if (provider.type().getClassLoader() != loader) continue; // already found on the classpath
                    out.add(new Candidate(provider.get(), jar.toString()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot open plugin jar " + jar, e);
            }
        }
        return out;
    }

    /**
     * @param discovered plugins found by discovery: loaded only when listed and enabled
     * @param explicit   plugins passed in code: loaded unless their entry says {@code "enabled": false}
     * @param plugins    the {@code plugins} node of the settings (null or absent = empty)
     * @throws IllegalStateException for a plugin without a valid id, or two plugins with one id
     */
    public static Result resolve(List<Candidate> discovered, List<Candidate> explicit, JsonNode plugins) {
        List<Candidate> all = new ArrayList<>(discovered);
        all.addAll(explicit);
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate c : all) { // id() first, for every plugin: a failure stops the process
            String id = c.plugin().id();
            if (id == null || !HarnessPlugin.ID_PATTERN.matcher(id).matches()) {
                throw new IllegalStateException("Plugin " + c.plugin().getClass().getName() + " has the invalid id '"
                        + id + "'; an id must match [a-z][a-z0-9-]{1,31}");
            }
            Candidate prev = byId.put(id, c);
            if (prev != null) {
                throw new IllegalStateException("Two plugins have the id '" + id + "': "
                        + prev.plugin().getClass().getName() + " (" + prev.source() + ") and "
                        + c.plugin().getClass().getName() + " (" + c.source() + ")");
            }
        }
        JsonNode section = plugins != null && plugins.isObject() ? plugins : JsonNodeFactory.instance.objectNode();
        List<Install> install = new ArrayList<>();
        List<String> warnings = new ArrayList<>(), infos = new ArrayList<>();
        List<String> loaded = new ArrayList<>(), disabled = new ArrayList<>(), notListed = new ArrayList<>();
        Set<String> explicitIds = new LinkedHashSet<>();
        explicit.forEach(c -> explicitIds.add(c.plugin().id()));
        for (Candidate c : all) {
            String id = c.plugin().id();
            JsonNode entry = section.get(id);
            boolean isExplicit = explicitIds.contains(id);
            JsonNode enabled = entry == null ? null : entry.get("enabled");
            boolean enabledBool = enabled != null && enabled.asBoolean(false);
            if (isExplicit) {
                if (enabled != null && !enabledBool) {
                    disabled.add(id);
                    infos.add("plugin '" + id + "' disabled in settings");
                    continue;
                }
            } else if (entry == null) {
                notListed.add(id);
                warnings.add("plugin '" + id + "' found (" + c.source() + ") but not listed under plugins; not loaded");
                continue;
            } else if (enabled == null) {
                notListed.add(id);
                warnings.add("plugin '" + id + "' has no 'enabled' under plugins; treated as false, not loaded");
                continue;
            } else if (!enabledBool) {
                disabled.add(id);
                infos.add("plugin '" + id + "' disabled in settings");
                continue;
            }
            JsonNode own = entry == null ? null : entry.get("settings");
            install.add(new Install(c.plugin(), own == null || own.isNull()
                    ? JsonNodeFactory.instance.objectNode() : own));
            loaded.add(id);
            if (c.source() != null && !c.source().equals("classpath") && !c.source().equals("code")) {
                infos.add("plugin '" + id + "' loaded from " + c.source());
            }
        }
        section.fieldNames().forEachRemaining(id -> {
            if (!byId.containsKey(id)) {
                warnings.add("plugin '" + id + "' is listed under plugins but was not found on the classpath or in "
                        + "the plugin directory");
            }
        });
        return new Result(install, warnings, infos, loaded, disabled, notListed);
    }
}

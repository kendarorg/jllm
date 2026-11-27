package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The writable side of the settings: the global settings an administrator edits at runtime (for example the role
 * mapping options, the providers, the docker host), kept next to the read-only {@link SettingsSource} layers.
 * <p>
 * <b>Granularity.</b> One JSON value per top-level section (the key it would have in a settings file):
 * {@link #put} replaces a section whole, {@link #remove} drops it, every other section is left as it is. A section
 * this code does not know is stored and returned unchanged.
 * <p>
 * <b>Precedence.</b> A stored value wins over the initial value from the settings files and the properties:
 * <ul>
 *     <li>In the harness settings the sections are the {@link SettingsLayer.Kind#STORED} layer, after the global,
 *     project and explicit layers ({@link #over}), merged like any layer: objects per key, other values (arrays
 *     included) replaced, {@code permissions.deny} the union of every layer.</li>
 *     <li>An option read from its section directly (a web option whose initial value is a property) takes the stored
 *     section when there is one, else its initial value.</li>
 * </ul>
 * <b>Reads.</b> {@link #get} and {@link #sections} read a snapshot in memory, never the store. Each write replaces it
 * in one atomic swap once the store has the change, then calls the {@link #onChange} listeners: a reader sees either
 * the old or the new sections, never a mix, and what it already holds does not change under it (values are copies).
 * {@link #reload} reads the store again (a change made by another node or by hand).
 * <p>
 * <b>Host sections.</b> A section whose name starts with {@value #HOST_PREFIX} belongs to the application hosting the
 * harness (the web server's role mapping options, for example): it is stored and read like any other, but it is left
 * out of the {@link SettingsLayer.Kind#STORED} layer, so the harness never sees it (nor reports it as an unknown
 * setting).
 * <p>
 * <b>Secrets.</b> No secret is stored as a plain value: a secret field holds an {@code env:NAME} reference, resolved
 * from the environment where it is used (as user credentials and the OIDC client secret are), never the secret itself.
 */
public interface SettingsSink {

    /** Prefix of the sections of the host application, kept out of the harness settings ({@link #layer}). */
    String HOST_PREFIX = "web.";

    /** Whether {@code section} belongs to the host application rather than to the harness settings. */
    static boolean hostSection(String section) {
        return section != null && section.startsWith(HOST_PREFIX);
    }

    /** @return a copy of the stored section; empty when it is not stored */
    Optional<JsonNode> get(String section);

    /** @return every stored section by name, sorted; the values are copies */
    Map<String, JsonNode> sections();

    /**
     * Stores (replaces) a section, atomically: the whole write is in the store, or none of it.
     *
     * @throws IllegalArgumentException for an invalid section name, a null or missing value, or a value over the size
     *                                  cap
     */
    void put(String section, JsonNode value);

    /** Removes a section, atomically. @return whether it was stored */
    boolean remove(String section);

    /** Reads the store again and swaps the snapshot (listeners called when it changed). */
    void reload();

    /** Called with the new sections after each change of the snapshot, in order of the changes. */
    void onChange(Consumer<Map<String, JsonNode>> listener);

    /** Where the sections are kept, for messages (a file path, {@code sql:settings_section}). */
    String origin();

    /**
     * @return the harness sections (all but the {@linkplain #hostSection host ones}) as the
     * {@link SettingsLayer.Kind#STORED} layer; empty when there is none
     */
    default Optional<SettingsLayer> layer() {
        ObjectNode tree = JsonNodeFactory.instance.objectNode();
        sections().forEach((name, value) -> {
            if (!hostSection(name)) tree.set(name, value);
        });
        if (tree.isEmpty()) return Optional.empty();
        return Optional.of(new SettingsLayer(SettingsLayer.Kind.STORED, origin(), tree));
    }

    /** @return the layers of {@code base}, then the stored layer (read when the layers are asked for) */
    default SettingsSource over(SettingsSource base) {
        return () -> {
            List<SettingsLayer> out = new ArrayList<>(base.layers());
            layer().ifPresent(out::add);
            return out;
        };
    }
}

package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The snapshot, the checks and the listeners of a {@link SettingsSink}; a subclass only reads and writes its store.
 * Writes of one instance are serialized: store write, store read, snapshot swap, listeners, in that order.
 */
public abstract class AbstractSettingsSink implements SettingsSink {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractSettingsSink.class);
    /** A section name: what a top-level settings key looks like. */
    private static final Pattern SECTION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    /** The largest section, serialized. */
    public static final int MAX_SECTION_BYTES = 1024 * 1024;

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final Object lock = new Object();
    private final List<Consumer<Map<String, JsonNode>>> listeners = new CopyOnWriteArrayList<>();
    /** Immutable once published; null until first read. */
    private volatile Map<String, JsonNode> snapshot;

    /** @return every section in the store */
    protected abstract Map<String, JsonNode> read();

    /** Writes one section ({@code value} null = removes it) in one atomic step. @return whether the section was stored before */
    protected abstract boolean write(String section, JsonNode value);

    @Override
    public Optional<JsonNode> get(String section) {
        JsonNode v = current().get(section);
        return v == null ? Optional.empty() : Optional.of(v.deepCopy());
    }

    @Override
    public Map<String, JsonNode> sections() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        current().forEach((k, v) -> out.put(k, v.deepCopy()));
        return out;
    }

    @Override
    public void put(String section, JsonNode value) {
        checkSection(section);
        if (value == null || value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Settings section '" + section + "' needs a value (remove it instead)");
        }
        if (serialize(value).getBytes(StandardCharsets.UTF_8).length > MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("Settings section '" + section + "' is over " + MAX_SECTION_BYTES + " bytes");
        }
        JsonNode copy = value.deepCopy();
        synchronized (lock) {
            write(section, copy);
            swap(read());
        }
    }

    @Override
    public boolean remove(String section) {
        checkSection(section);
        synchronized (lock) {
            boolean was = write(section, null);
            swap(read());
            return was;
        }
    }

    @Override
    public void reload() {
        synchronized (lock) {
            swap(read());
        }
    }

    @Override
    public void onChange(Consumer<Map<String, JsonNode>> listener) {
        listeners.add(listener);
    }

    private Map<String, JsonNode> current() {
        Map<String, JsonNode> s = snapshot;
        if (s != null) return s;
        synchronized (lock) {
            if (snapshot == null) snapshot = freeze(read());
            return snapshot;
        }
    }

    /** Publishes the new sections; listeners only when they differ from the old ones. Called holding the lock. */
    private void swap(Map<String, JsonNode> read) {
        Map<String, JsonNode> next = freeze(read);
        Map<String, JsonNode> old = snapshot;
        snapshot = next;
        if (next.equals(old)) return;
        for (Consumer<Map<String, JsonNode>> l : listeners) {
            try {
                l.accept(sections());
            } catch (RuntimeException e) { // boundary: one listener must not stop the others nor fail the write
                LOG.warn("Settings listener failed on a change of {}: {}", origin(), e.toString());
            }
        }
    }

    private static Map<String, JsonNode> freeze(Map<String, JsonNode> read) {
        Map<String, JsonNode> m = new TreeMap<>();
        read.forEach((k, v) -> m.put(k, v.deepCopy()));
        return Collections.unmodifiableMap(m);
    }

    /** @throws IllegalArgumentException when {@code section} is not a valid section name */
    public static void checkSection(String section) {
        if (section == null || !SECTION.matcher(section).matches()) {
            throw new IllegalArgumentException("Invalid settings section '" + section
                    + "': letters, digits, '_', '-' and '.', up to 128, starting with a letter or digit");
        }
    }

    protected static String serialize(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Settings value not serializable", e);
        }
    }
}

package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link SettingsSink} in one JSON file: an object of section name to value. Each write reads the file, changes one
 * section and writes the whole object to a temporary file in the same folder, then moves it over the file: a reader
 * (and a crash) sees the old file or the new one, never half of one. Writes to the same file are serialized in this
 * JVM; the file store serves a single node. A missing file is an empty sink.
 */
public final class FileSettingsSink extends AbstractSettingsSink {

    /** One lock per file, shared by every instance on it. */
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    public FileSettingsSink(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    @Override
    public String origin() {
        return file.toString();
    }

    @Override
    protected Map<String, JsonNode> read() {
        synchronized (lock()) {
            Map<String, JsonNode> out = new LinkedHashMap<>();
            readObject().fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
            return out;
        }
    }

    @Override
    protected boolean write(String section, JsonNode value) {
        synchronized (lock()) {
            ObjectNode doc = readObject();
            boolean was = doc.has(section);
            if (value == null) {
                if (!was) return false;
                doc.remove(section);
            } else {
                doc.set(section, value);
            }
            try {
                Files.createDirectories(file.getParent());
                Path tmp = Files.createTempFile(file.getParent(), ".settings-", ".part");
                try {
                    Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc),
                            StandardCharsets.UTF_8);
                    try {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write settings " + file, e);
            }
            return was;
        }
    }

    private Object lock() {
        return LOCKS.computeIfAbsent(file, k -> new Object());
    }

    /** Reads through NIO (which lets the file be replaced while open, also on Windows). */
    private ObjectNode readObject() {
        if (!Files.isRegularFile(file)) return MAPPER.createObjectNode();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return MAPPER.createObjectNode();
            JsonNode tree = MAPPER.readTree(text);
            if (tree instanceof ObjectNode o) return o;
            throw new IllegalStateException("Settings " + file + " must hold a JSON object");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings " + file, e);
        }
    }
}

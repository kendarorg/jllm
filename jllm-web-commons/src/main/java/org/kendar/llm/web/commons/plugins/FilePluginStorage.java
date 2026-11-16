package org.kendar.llm.web.commons.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.plugin.PluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Plugin storage as files: {@code root/<plugin>/<owner>/<collection>/<id>.json}. Names are encoded, never trusted. */
public final class FilePluginStorage implements PluginStorages {
    private final Path root;
    private final ObjectMapper mapper;

    public FilePluginStorage(Path root, ObjectMapper mapper) {
        this.root = root;
        this.mapper = mapper;
    }

    @Override
    public PluginStorage forPlugin(String pluginId) {
        return new Area(root.resolve(enc(pluginId)));
    }

    /** Reversible and filesystem-safe: anything outside [A-Za-z0-9-] becomes _xx (hex). */
    static String enc(String s) {
        StringBuilder b = new StringBuilder();
        for (byte c : s.getBytes(StandardCharsets.UTF_8)) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-') b.append((char) c);
            else b.append('_').append(String.format("%02x", c & 0xff));
        }
        return b.isEmpty() ? "_" : b.toString();
    }

    static String dec(String s) {
        if (s.equals("_")) return "";
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                o.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else o.write(c);
        }
        return o.toString(StandardCharsets.UTF_8);
    }

    private final class Area implements PluginStorage {
        private final Path base;

        Area(Path base) {
            this.base = base;
        }

        private Path dir(String owner, String collection) {
            return base.resolve(enc(owner)).resolve(enc(collection));
        }

        @Override
        public Optional<JsonNode> get(String owner, String collection, String id) {
            Path f = dir(owner, collection).resolve(enc(id) + ".json");
            if (!Files.exists(f)) return Optional.empty();
            try {
                return Optional.of(mapper.readTree(Files.readString(f)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void put(String owner, String collection, String id, JsonNode doc) {
            Path d = dir(owner, collection);
            try {
                Files.createDirectories(d);
                Path tmp = Files.createTempFile(d, "doc", ".tmp");
                Files.writeString(tmp, mapper.writeValueAsString(doc));
                Path target = d.resolve(enc(id) + ".json");
                try { // atomic: a reader never finds the document missing while it is being replaced
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void delete(String owner, String collection, String id) {
            try {
                Files.deleteIfExists(dir(owner, collection).resolve(enc(id) + ".json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public List<String> ids(String owner, String collection) {
            Path d = dir(owner, collection);
            if (!Files.isDirectory(d)) return List.of();
            try (Stream<Path> s = Files.list(d)) {
                return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                        .map(n -> dec(n.substring(0, n.length() - 5))).sorted().toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public List<String> owners(String collection) {
            if (!Files.isDirectory(base)) return List.of();
            try (Stream<Path> s = Files.list(base)) {
                return s.filter(p -> Files.isDirectory(p.resolve(enc(collection)))).map(p -> dec(p.getFileName().toString()))
                        .sorted().toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}

package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** One file per user under the users directory: {@code <id>.json}. Read on each use, so edits apply at once. */
public final class UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z0-9_-]{1,64}");

    private final Path dir;
    private final ObjectMapper mapper;

    public UserDirectory(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    public Optional<WebUser> find(String id) {
        if (id == null || !ID.matcher(id).matches()) return Optional.empty();
        Path file = dir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(parse(id, mapper.readTree(file.toFile())));
        } catch (IOException | RuntimeException e) {
            log.warn("User file {} skipped: {}", file, e.toString());
            return Optional.empty();
        }
    }

    public List<WebUser> all() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .filter(n -> ID.matcher(n).matches())
                    .sorted()
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static WebUser parse(String fileId, JsonNode n) {
        Map<String, Map<String, String>> creds = new LinkedHashMap<>();
        JsonNode c = n.path("credentials");
        c.fields().forEachRemaining(source -> {
            Map<String, String> headers = new LinkedHashMap<>();
            source.getValue().fields().forEachRemaining(h -> headers.put(h.getKey(), h.getValue().asText()));
            creds.put(source.getKey(), headers);
        });
        return new WebUser(fileId, n.path("name").asText(fileId), n.path("role").asText("none"), creds);
    }
}

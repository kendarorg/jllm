package org.kendar.llm.web.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One file per user under the users directory: {@code <id>.json}. Read on each use, so edits apply at once. Writes
 * keep the fields they do not touch (e.g. {@code credentials}).
 */
public final class UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");

    private final Path dir;
    private final ObjectMapper mapper;

    public UserDirectory(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public Optional<WebUser> find(String id) {
        if (!validId(id)) return Optional.empty();
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
                    .filter(UserDirectory::validId)
                    .sorted()
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public boolean exists(String id) {
        return validId(id) && Files.isRegularFile(dir.resolve(id + ".json"));
    }

    /** Creates a new user file. @throws IllegalArgumentException on an invalid id or an existing user */
    public WebUser create(String id, String name, String role, String passwordHash) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid user id: " + id);
        synchronized (JsonFiles.LOCK) {
            if (exists(id)) throw new IllegalStateException("User " + id + " exists");
            JsonFiles.update(mapper, dir.resolve(id + ".json"), n -> {
                n.put("name", name == null || name.isBlank() ? id : name);
                n.put("role", role);
                if (passwordHash != null) n.put("password", passwordHash);
                n.putObject("credentials");
            });
        }
        return find(id).orElseThrow();
    }

    /** Changes fields of an existing user file. @throws NoSuchElementException when the user does not exist */
    public WebUser update(String id, Consumer<ObjectNode> change) {
        synchronized (JsonFiles.LOCK) {
            if (!exists(id)) throw new NoSuchElementException("Unknown user " + id);
            JsonFiles.update(mapper, dir.resolve(id + ".json"), change);
        }
        return find(id).orElseThrow();
    }

    public WebUser setPassword(String id, String passwordHash) {
        return update(id, n -> {
            if (passwordHash == null) n.remove("password");
            else n.put("password", passwordHash);
        });
    }

    public WebUser setList(String id, String field, Collection<String> values) {
        return update(id, n -> {
            ArrayNode a = n.putArray(field);
            values.forEach(a::add);
        });
    }

    public boolean delete(String id) {
        return validId(id) && JsonFiles.delete(dir.resolve(id + ".json"));
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) n.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static WebUser parse(String fileId, JsonNode n) {
        Map<String, Map<String, String>> creds = new LinkedHashMap<>();
        JsonNode c = n.path("credentials");
        c.fields().forEachRemaining(source -> {
            Map<String, String> headers = new LinkedHashMap<>();
            source.getValue().fields().forEachRemaining(h -> headers.put(h.getKey(), h.getValue().asText()));
            creds.put(source.getKey(), headers);
        });
        String password = n.path("password").isTextual() ? n.path("password").asText() : null;
        return new WebUser(fileId, n.path("name").asText(fileId), n.path("role").asText("none"), creds, password,
                strings(n.path("disabledTools")), strings(n.path("disabledSkills")));
    }
}

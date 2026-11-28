package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@link FolderTrust} answers in the user's {@code <jllm.home or user.home>/.jllm/trusted.json}: per canonical
 * folder path the {@code folder} record (hash, time, approved loosenings) and, per name, the answer about a
 * definition with the hash it was given for.
 */
public final class FileFolderTrust implements FolderTrust {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FOLDER = "folder";
    private static final String DEFS = "definitions";

    private final Path file;

    public FileFolderTrust(Path file) {
        this.file = file;
    }

    /** {@code <jllm.home or user.home>/.jllm/trusted.json} */
    public static FileFolderTrust user() {
        return new FileFolderTrust(Path.of(System.getProperty("jllm.home", System.getProperty("user.home")),
                ".jllm", "trusted.json"));
    }

    @Override
    public synchronized Boolean lookup(Path projectDir, String server, JsonNode definition) {
        JsonNode e = read().path(FolderTrust.projectKey(projectDir)).path(DEFS).path(server);
        if (!e.isObject() || !FolderTrust.fingerprint(definition).equals(e.path("sha256").asText())) return null;
        return e.path("trusted").isBoolean() ? e.get("trusted").asBoolean() : null;
    }

    @Override
    public synchronized void remember(Path projectDir, String server, JsonNode definition, boolean trusted) {
        ObjectNode root = read();
        ObjectNode project = project(root, projectDir);
        JsonNode d = project.get(DEFS);
        ObjectNode defs = d instanceof ObjectNode o ? o : project.putObject(DEFS);
        ObjectNode e = defs.putObject(server);
        e.put("sha256", FolderTrust.fingerprint(definition));
        e.put("trusted", trusted);
        write(root);
    }

    @Override
    public synchronized Optional<Record> record(Path projectDir) {
        String key = FolderTrust.projectKey(projectDir);
        JsonNode f = read().path(key).path(FOLDER);
        if (!f.isObject() || !f.path("sha256").isTextual()) return Optional.empty();
        Set<String> loose = new TreeSet<>();
        f.path("loosenings").forEach(n -> loose.add(n.asText()));
        return Optional.of(new Record(key, f.get("sha256").asText(), f.path("trustedAt").asLong(0), loose));
    }

    @Override
    public synchronized void trust(Path projectDir, Set<String> loosenings) {
        ObjectNode root = read();
        ObjectNode f = project(root, projectDir).putObject(FOLDER);
        f.put("path", FolderTrust.projectKey(projectDir));
        f.put("sha256", FolderTrust.hashOf(projectDir));
        f.put("trustedAt", System.currentTimeMillis());
        ArrayNode l = f.putArray("loosenings");
        new TreeSet<>(loosenings).forEach(l::add);
        write(root);
    }

    @Override
    public synchronized void approveLoosening(Path projectDir, String key) {
        Optional<Record> r = record(projectDir);
        if (r.isEmpty() || !r.get().sha256().equals(FolderTrust.hashOf(projectDir))) return;
        ObjectNode root = read();
        ObjectNode f = (ObjectNode) root.get(r.get().path()).get(FOLDER);
        Set<String> all = new TreeSet<>(r.get().loosenings());
        all.add(key);
        ArrayNode l = f.putArray("loosenings");
        all.forEach(l::add);
        write(root);
    }

    private static ObjectNode project(ObjectNode root, Path projectDir) {
        String key = FolderTrust.projectKey(projectDir);
        JsonNode p = root.get(key);
        return p instanceof ObjectNode o ? o : root.putObject(key);
    }

    private void write(ObjectNode root) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "trusted", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            org.slf4j.LoggerFactory.getLogger(FileFolderTrust.class)
                    .warn("Cannot remember the trust answer in {}: {}", file, ex.toString());
        }
    }

    private ObjectNode read() {
        if (!Files.isRegularFile(file)) return MAPPER.createObjectNode();
        try {
            JsonNode n = MAPPER.readTree(file.toFile());
            return n instanceof ObjectNode o ? o : MAPPER.createObjectNode();
        } catch (IOException e) {
            return MAPPER.createObjectNode(); // unreadable: ask again
        }
    }
}

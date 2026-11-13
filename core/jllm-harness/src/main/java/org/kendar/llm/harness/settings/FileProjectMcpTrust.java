package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The {@link ProjectMcpTrust} answers in the user's {@code <jllm.home or user.home>/.jllm/trusted-mcp.json}: per
 * project, per name, the answer and the hash of the definition it was given for.
 */
public final class FileProjectMcpTrust implements ProjectMcpTrust {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public FileProjectMcpTrust(Path file) {
        this.file = file;
    }

    /** {@code <jllm.home or user.home>/.jllm/trusted-mcp.json} */
    public static FileProjectMcpTrust user() {
        return new FileProjectMcpTrust(Path.of(System.getProperty("jllm.home", System.getProperty("user.home")),
                ".jllm", "trusted-mcp.json"));
    }

    @Override
    public synchronized Boolean lookup(Path projectDir, String server, JsonNode definition) {
        JsonNode e = read().path(key(projectDir)).path(server);
        if (!e.isObject() || !ProjectMcpTrust.fingerprint(definition).equals(e.path("sha256").asText())) return null;
        return e.path("trusted").isBoolean() ? e.get("trusted").asBoolean() : null;
    }

    @Override
    public synchronized void remember(Path projectDir, String server, JsonNode definition, boolean trusted) {
        ObjectNode root = read();
        JsonNode p = root.get(key(projectDir));
        ObjectNode project = p instanceof ObjectNode o ? o : root.putObject(key(projectDir));
        ObjectNode e = project.putObject(server);
        e.put("sha256", ProjectMcpTrust.fingerprint(definition));
        e.put("trusted", trusted);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "trusted-mcp", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            org.slf4j.LoggerFactory.getLogger(FileProjectMcpTrust.class)
                    .warn("Cannot remember the MCP trust answer in {}: {}", file, ex.toString());
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

    private static String key(Path projectDir) {
        return ProjectMcpTrust.projectKey(projectDir);
    }
}

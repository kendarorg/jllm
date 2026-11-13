package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stdio MCP servers defined in a project's own {@code .jllm/settings.json} start a process chosen by whoever wrote the
 * project: they run only once the user said so. The answer is remembered per project and server in the user's
 * {@code <jllm.home or user.home>/.jllm/trusted-mcp.json}, bound to a hash of the server definition: a changed
 * command, argument list or environment asks again.
 */
public final class ProjectMcpTrust {

    /** Asks the user whether a project's stdio MCP server may run. */
    @FunctionalInterface
    public interface Prompt {

        /** No one to ask (web server, scripts): only an answer remembered earlier counts. */
        Prompt NONE = (projectDir, server, commandLine) -> null;

        /**
         * @param commandLine the command and its arguments, for display
         * @return true to run the server (remembered), false to refuse it (remembered), null when no one can answer
         * (nothing remembered; the server is skipped)
         */
        Boolean ask(Path projectDir, String server, String commandLine);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public ProjectMcpTrust(Path file) {
        this.file = file;
    }

    /** {@code <jllm.home or user.home>/.jllm/trusted-mcp.json} */
    public static ProjectMcpTrust user() {
        return new ProjectMcpTrust(Path.of(System.getProperty("jllm.home", System.getProperty("user.home")), ".jllm",
                "trusted-mcp.json"));
    }

    /** The remembered answer for this exact definition, or null when there is none. */
    public synchronized Boolean lookup(Path projectDir, String server, JsonNode definition) {
        JsonNode e = read().path(key(projectDir)).path(server);
        if (!e.isObject() || !fingerprint(definition).equals(e.path("sha256").asText())) return null;
        return e.path("trusted").isBoolean() ? e.get("trusted").asBoolean() : null;
    }

    public synchronized void remember(Path projectDir, String server, JsonNode definition, boolean trusted) {
        ObjectNode root = read();
        JsonNode p = root.get(key(projectDir));
        ObjectNode project = p instanceof ObjectNode o ? o : root.putObject(key(projectDir));
        ObjectNode e = project.putObject(server);
        e.put("sha256", fingerprint(definition));
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
            org.slf4j.LoggerFactory.getLogger(ProjectMcpTrust.class)
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
        Path p = projectDir.toAbsolutePath().normalize();
        try {
            p = p.toRealPath();
        } catch (IOException | SecurityException ignored) {
            // not existing: the normalized path
        }
        return p.toString();
    }

    static String fingerprint(JsonNode definition) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(definition).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

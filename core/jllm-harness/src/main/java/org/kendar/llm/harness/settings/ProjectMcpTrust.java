package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stdio MCP servers (and stop hooks, OAuth blocks) defined in a project's own settings run a process or send a token
 * where whoever wrote the project chose: they count only once the user said so. The answer is remembered per project
 * and name, bound to a hash of the definition ({@link #fingerprint}): a changed command, argument list or environment
 * asks again. {@link FileProjectMcpTrust} keeps the answers in a file of the user.
 */
public interface ProjectMcpTrust {

    /** Asks the user whether a project's stdio MCP server may run. */
    @FunctionalInterface
    interface Prompt {

        /** No one to ask (web server, scripts): only an answer remembered earlier counts. */
        Prompt NONE = (projectDir, server, commandLine) -> null;

        /**
         * @param commandLine the command and its arguments, for display
         * @return true to run the server (remembered), false to refuse it (remembered), null when no one can answer
         * (nothing remembered; the server is skipped)
         */
        Boolean ask(Path projectDir, String server, String commandLine);
    }

    /** The remembered answer for this exact definition, or null when there is none. */
    Boolean lookup(Path projectDir, String server, JsonNode definition);

    /** Remembers the answer for this definition; a failure to store it is logged, not thrown (it is asked again). */
    void remember(Path projectDir, String server, JsonNode definition, boolean trusted);

    /** The key of a project: its real absolute path, or the normalized one when it does not exist. */
    static String projectKey(Path projectDir) {
        Path p = projectDir.toAbsolutePath().normalize();
        try {
            p = p.toRealPath();
        } catch (IOException | SecurityException ignored) {
            // not existing: the normalized path
        }
        return p.toString();
    }

    /** SHA-256 (hex) of the definition's JSON: what an answer is bound to. */
    static String fingerprint(JsonNode definition) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsString(definition).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
